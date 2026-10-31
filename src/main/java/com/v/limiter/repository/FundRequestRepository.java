package com.v.limiter.repository;

import com.v.limiter.model.FundRequest;
import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.CustomerLoadStats;
import com.v.limiter.model.FundAcceptanceResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.UUID;

@Slf4j
@Repository
public class FundRequestRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FundRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public boolean saveMessage(FundRequest request, String payload) {
        if (request.getId() == null || request.getCustomerId() == null
                || request.getAmount() == null || request.getCurrency() == null) {
            throw new IllegalArgumentException("Request is missing a required message field");
        }

        int inserted = jdbcTemplate.update("""
        INSERT INTO messages (load_id, customer_id, load_amount, load_currency, payload)
        SELECT ?, ?, ?, ?, ?
        WHERE NOT EXISTS (
            SELECT 1 FROM messages WHERE load_id = ? AND customer_id = ?
        )
        """,
                request.getId(), request.getCustomerId(), request.getAmount(),
                request.getCurrency().getCurrencyCode(), payload,
                request.getId(), request.getCustomerId()
        );

        if (inserted == 0) {
            log.info("Duplicated fund request for load id {} skipping request", request.getId());
            return false;
        }

        jdbcTemplate.update("""
        INSERT INTO customer_state (customer_id)
        SELECT ?
        WHERE NOT EXISTS (
            SELECT 1 FROM customer_state WHERE customer_id = ?
        )
        """,
                request.getCustomerId(), request.getCustomerId()
        );
        return true;
    }

    @Transactional
    public ClaimedFund claimOne() {
        ClaimCandidate candidate = jdbcTemplate.query("""
                SELECT m.message_id, m.customer_id
                FROM messages m
                JOIN customer_state cs ON cs.customer_id = m.customer_id
                WHERE (
                    m.status = 'NEW'
                    OR (m.status = 'PROCESSING' AND m.locked_until < CURRENT_TIMESTAMP)
                )
                  AND m.attempt_count < 10
                  AND (cs.locked_until IS NULL OR cs.locked_until < CURRENT_TIMESTAMP)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM messages earlier
                      WHERE earlier.customer_id = m.customer_id
                        AND earlier.status <> 'DONE'
                        AND earlier.attempt_count < 10
                        AND (
                            earlier.received_at < m.received_at
                            OR (earlier.received_at = m.received_at
                                AND earlier.message_id < m.message_id)
                        )
                  )
                ORDER BY m.received_at, m.message_id
                LIMIT 1
                """,
                rs -> rs.next()
                        ? new ClaimCandidate(rs.getLong("message_id"), rs.getLong("customer_id"))
                        : null);

        if (candidate == null) {
            return null;
        }

        UUID claimId = UUID.randomUUID();
        int customerLocked = jdbcTemplate.update("""
                UPDATE customer_state
                SET locked_until = DATEADD('MINUTE', 5, CURRENT_TIMESTAMP), lock_token = ?
                WHERE customer_id = ?
                  AND (locked_until IS NULL OR locked_until < CURRENT_TIMESTAMP)
                """, claimId, candidate.customerId());
        if (customerLocked == 0) {
            return null;
        }

        int messageClaimed = jdbcTemplate.update("""
                UPDATE messages
                SET status = 'PROCESSING',
                    locked_until = DATEADD('MINUTE', 5, CURRENT_TIMESTAMP),
                    claim_id = ?,
                    attempt_count = attempt_count + 1
                WHERE message_id = ?
                  AND customer_id = ?
                  AND attempt_count < 10
                  AND (status = 'NEW'
                       OR (status = 'PROCESSING' AND locked_until < CURRENT_TIMESTAMP))
                """, claimId, candidate.messageId(), candidate.customerId());

        if (messageClaimed == 0) {
            jdbcTemplate.update("""
                    UPDATE customer_state
                    SET locked_until = NULL, lock_token = NULL
                    WHERE customer_id = ? AND lock_token = ?
                    """, candidate.customerId(), claimId);
            return null;
        }

        FundRequest fundRequest = jdbcTemplate.queryForObject("""
                SELECT message_id, load_id, customer_id, load_amount, load_currency,
                       payload
                FROM messages
                WHERE message_id = ? AND claim_id = ?
                """, (rs, rowNum) -> mapClaimedMessage(rs), candidate.messageId(), claimId);

        CustomerLoadStats currentStats = jdbcTemplate.queryForObject("""
                SELECT last_load_timestamp, total_loads_day, total_loads_week, count_loads_day
                FROM customer_state
                WHERE customer_id = ? AND lock_token = ?
                """, (rs, rowNum) -> {
                    Timestamp lastLoadTimestamp = rs.getTimestamp("last_load_timestamp");
                    return new CustomerLoadStats(
                            lastLoadTimestamp == null ? null : lastLoadTimestamp.toInstant(),
                            rs.getBigDecimal("total_loads_day"),
                            rs.getBigDecimal("total_loads_week"),
                            rs.getInt("count_loads_day"));
                }, candidate.customerId(), claimId);
        return new ClaimedFund(fundRequest, claimId, currentStats);
    }

    private FundRequest mapClaimedMessage(ResultSet rs) throws SQLException {
        String payload = rs.getString("payload");
        FundRequest request;
        try {
            request = objectMapper.readValue(payload, FundRequest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored message payload is not valid JSON", e);
        }
        request.setMessageId(rs.getLong("message_id"));
        request.setId(rs.getLong("load_id"));
        request.setCustomerId(rs.getLong("customer_id"));
        request.setAmount(rs.getBigDecimal("load_amount"));
        request.setCurrency(Currency.getInstance(rs.getString("load_currency")));
        request.setPayload(payload);
        return request;
    }

    @Transactional
    public boolean markDone(ClaimedFund claimedFund, FundAcceptanceResult result) {
        if (result == null) {
            throw new IllegalArgumentException("Fund acceptance result is required to mark a message done");
        }
        CustomerLoadStats loadStats = result.newStats();
        if (result.accepted() && (loadStats == null || loadStats.lastLoadTimestamp() == null
                || loadStats.totalLoadsDay() == null || loadStats.totalLoadsWeek() == null)) {
            throw new IllegalArgumentException("Accepted funds require updated customer load statistics");
        }

        FundRequest request = claimedFund.fundRequest();
        int markedDone = jdbcTemplate.update("""
                UPDATE messages
                SET status = 'DONE', accepted = ?, locked_until = NULL
                WHERE message_id = ?
                  AND status = 'PROCESSING'
                  AND claim_id = ?
                """, result.accepted(), request.getMessageId(), claimedFund.claimId());

        if (markedDone == 0) {
            return false;
        }

        int updatedCustomer;
        if (result.accepted()) {
            updatedCustomer = jdbcTemplate.update("""
                UPDATE customer_state
                SET locked_until = NULL,
                    lock_token = NULL,
                    last_load_timestamp = ?,
                    total_loads_day = ?,
                    total_loads_week = ?,
                    count_loads_day = ?
                WHERE customer_id = ?
                  AND lock_token = ?
                """, Timestamp.from(loadStats.lastLoadTimestamp()),
                loadStats.totalLoadsDay(),
                loadStats.totalLoadsWeek(), loadStats.countLoadsDay(),
                request.getCustomerId(), claimedFund.claimId());
        } else {
            updatedCustomer = jdbcTemplate.update("""
                    UPDATE customer_state
                    SET locked_until = NULL, lock_token = NULL
                    WHERE customer_id = ? AND lock_token = ?
                    """, request.getCustomerId(), claimedFund.claimId());
        }

        if (updatedCustomer == 0) {
            throw new IllegalStateException("Customer lock no longer belongs to claim " + claimedFund.claimId());
        }
        return true;
    }

    @Transactional
    public void releaseForRetry(ClaimedFund message) {
        int updated = jdbcTemplate.update("""
        UPDATE messages
        SET status = 'NEW',
            locked_until = NULL,
            claim_id = NULL
        WHERE message_id = ?
          AND status = 'PROCESSING'
          AND claim_id = ?
        """,
                message.fundRequest().getMessageId(),
                message.claimId()
        );

        if (updated == 0) {
            return;
        }

        jdbcTemplate.update("""
        UPDATE customer_state
        SET locked_until = NULL,
            lock_token = NULL
        WHERE customer_id = ?
          AND lock_token = ?
        """,
                message.fundRequest().getCustomerId(),
                message.claimId()
        );
    }

    private record ClaimCandidate(long messageId, long customerId) {
    }
}
