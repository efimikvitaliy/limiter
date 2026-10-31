package com.v.limiter.repository;

import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.CustomerLoadStats;
import com.v.limiter.model.FundAcceptanceResult;
import com.v.limiter.model.FundRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.UUID;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FundRequestRepositoryTest {
    private static final long CUSTOMER_ID = 1234L;

    private EmbeddedDatabase database;
    private JdbcTemplate jdbcTemplate;
    private FundRequestRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("schema.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(database);
        repository = new FundRequestRepository(jdbcTemplate);
        jdbcTemplate.update("INSERT INTO customer_state (customer_id) VALUES (?)", CUSTOMER_ID);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void claimsNewMessageAndUsesClaimUuidForMessageAndCustomerLock() {
        insertMessage(1001L, "DONE", LocalDateTime.of(2024, 1, 1, 0, 0));
        insertMessage(1002L, "NEW", LocalDateTime.of(2024, 1, 1, 0, 1));

        ClaimedFund claimedFund = repository.claimOne();

        assertNotNull(claimedFund);
        assertEquals(1002L, claimedFund.fundRequest().getId());
        assertNotNull(claimedFund.claimId());

        ClaimedFund persistedClaim = loadClaimedFund(1002L);
        assertEquals(claimedFund.claimId(), persistedClaim.claimId());
        assertEquals(claimedFund.claimId(), loadCustomerLockToken(CUSTOMER_ID));
        assertEquals(1002L, persistedClaim.fundRequest().getId());
        assertEquals(CUSTOMER_ID, persistedClaim.fundRequest().getCustomerId());
        assertEquals(new BigDecimal("12.34"), persistedClaim.fundRequest().getAmount());
        assertEquals("USD", persistedClaim.fundRequest().getCurrency().getCurrencyCode());
        assertEquals("PROCESSING", jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE load_id = ?", String.class, 1002L));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM messages WHERE load_id = ?", Integer.class, 1002L));
    }

    @Test
    void laterNewMessageCannotPassEarlierUnfinishedMessage() {
        insertMessage(1001L, "PROCESSING", LocalDateTime.of(2024, 1, 1, 0, 0));
        insertMessage(1002L, "NEW", LocalDateTime.of(2024, 1, 1, 0, 1));

        ClaimedFund claimedFund = repository.claimOne();

        assertNull(claimedFund);
        assertEquals("NEW", jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE load_id = ?", String.class, 1002L));
    }

    @Test
    void markDoneAcceptsMessageAndUpdatesCustomerStats() {
        insertMessage(1003L, "NEW", LocalDateTime.of(2024, 1, 1, 0, 0));
        ClaimedFund claimedFund = repository.claimOne();
        Instant timestamp = Instant.parse("2024-01-01T00:00:00Z");
        CustomerLoadStats updatedStats = new CustomerLoadStats(
                timestamp, new BigDecimal("123.45"), new BigDecimal("123.45"), 1);
        FundAcceptanceResult result = new FundAcceptanceResult(1003L, CUSTOMER_ID, true, updatedStats);

        assertTrue(repository.markDone(claimedFund, result));

        FundAcceptanceResult persistedResult = loadAcceptanceResult(1003L);
        assertEquals(1003L, persistedResult.id());
        assertEquals(CUSTOMER_ID, persistedResult.customerId());
        assertTrue(persistedResult.accepted());
        assertEquals(updatedStats, persistedResult.newStats());
        assertEquals("DONE", jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE load_id = ?", String.class, 1003L));
        assertEquals(claimedFund.claimId(), loadClaimedFund(1003L).claimId());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT lock_token FROM customer_state WHERE customer_id = ?", UUID.class, CUSTOMER_ID));
        assertEquals(new BigDecimal("123.45"), jdbcTemplate.queryForObject(
                "SELECT total_loads_day FROM customer_state WHERE customer_id = ?", BigDecimal.class, CUSTOMER_ID));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count_loads_day FROM customer_state WHERE customer_id = ?", Integer.class, CUSTOMER_ID));
    }

    @Test
    void markDoneRejectsMessageWithoutChangingCustomerStats() {
        jdbcTemplate.update("""
                UPDATE customer_state
                SET total_loads_day = 321.00, total_loads_week = 654.00, count_loads_day = 2
                WHERE customer_id = ?
                """, CUSTOMER_ID);
        insertMessage(1004L, "NEW", LocalDateTime.of(2024, 1, 1, 0, 0));
        ClaimedFund claimedFund = repository.claimOne();
        FundAcceptanceResult result = new FundAcceptanceResult(
                1004L, CUSTOMER_ID, false, claimedFund.customerLoadStats());

        assertTrue(repository.markDone(claimedFund, result));

        FundAcceptanceResult persistedResult = loadAcceptanceResult(1004L);
        assertEquals(1004L, persistedResult.id());
        assertEquals(CUSTOMER_ID, persistedResult.customerId());
        assertFalse(persistedResult.accepted());
        assertEquals(claimedFund.customerLoadStats(), persistedResult.newStats());
        assertEquals("DONE", jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE load_id = ?", String.class, 1004L));
        assertEquals(new BigDecimal("321.00"), persistedResult.newStats().totalLoadsDay());
        assertEquals(new BigDecimal("654.00"), persistedResult.newStats().totalLoadsWeek());
        assertEquals(2, persistedResult.newStats().countLoadsDay());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT lock_token FROM customer_state WHERE customer_id = ?", UUID.class, CUSTOMER_ID));
    }

    @Test
    void releaseForRetryResetsClaimWithoutIncrementingAttemptCount() {
        insertMessage(1005L, "NEW", LocalDateTime.of(2024, 1, 1, 0, 0));
        ClaimedFund claimedFund = repository.claimOne();

        repository.releaseForRetry(claimedFund);

        assertEquals("NEW", jdbcTemplate.queryForObject(
                "SELECT status FROM messages WHERE load_id = ?", String.class, 1005L));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT claim_id FROM messages WHERE load_id = ?", UUID.class, 1005L));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT locked_until FROM messages WHERE load_id = ?", Timestamp.class, 1005L));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM messages WHERE load_id = ?", Integer.class, 1005L));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT lock_token FROM customer_state WHERE customer_id = ?", UUID.class, CUSTOMER_ID));
        assertNull(jdbcTemplate.queryForObject(
                "SELECT locked_until FROM customer_state WHERE customer_id = ?", Timestamp.class, CUSTOMER_ID));
    }

    private void insertMessage(long loadId, String status, LocalDateTime receivedAt) {
        String payload = "{\"id\":\"" + loadId + "\",\"customer_id\":\"" + CUSTOMER_ID
                + "\",\"load_amount\":\"$12.34\",\"time\":\"2024-01-01T00:00:00Z\"}";
        jdbcTemplate.update("""
                INSERT INTO messages (load_id, customer_id, load_amount, load_currency,
                                      payload, received_at, status)
                VALUES (?, ?, ?, 'USD', ?, ?, ?)
                """, loadId, CUSTOMER_ID, new BigDecimal("12.34"), payload,
                Timestamp.valueOf(receivedAt), status);
    }

    private ClaimedFund loadClaimedFund(long loadId) {
        return jdbcTemplate.queryForObject("""
                SELECT m.message_id, m.load_id, m.customer_id, m.load_amount, m.load_currency,
                       m.payload, m.claim_id, cs.last_load_timestamp, cs.total_loads_day,
                       cs.total_loads_week, cs.count_loads_day
                FROM messages m
                JOIN customer_state cs ON cs.customer_id = m.customer_id
                WHERE m.load_id = ?
                """, (rs, rowNum) -> {
            FundRequest request = mapFundRequest(rs);
            Timestamp lastLoadTimestamp = rs.getTimestamp("last_load_timestamp");
            CustomerLoadStats stats = new CustomerLoadStats(
                    lastLoadTimestamp == null ? null : lastLoadTimestamp.toInstant(),
                    rs.getBigDecimal("total_loads_day"),
                    rs.getBigDecimal("total_loads_week"),
                    rs.getInt("count_loads_day"));
            return new ClaimedFund(request, rs.getObject("claim_id", UUID.class), stats);
        }, loadId);
    }

    private FundAcceptanceResult loadAcceptanceResult(long loadId) {
        return jdbcTemplate.queryForObject("""
                SELECT m.load_id, m.customer_id, m.accepted, cs.last_load_timestamp,
                       cs.total_loads_day, cs.total_loads_week, cs.count_loads_day
                FROM messages m
                JOIN customer_state cs ON cs.customer_id = m.customer_id
                WHERE m.load_id = ?
                """, (rs, rowNum) -> {
            Timestamp lastLoadTimestamp = rs.getTimestamp("last_load_timestamp");
            CustomerLoadStats stats = new CustomerLoadStats(
                    lastLoadTimestamp == null ? null : lastLoadTimestamp.toInstant(),
                    rs.getBigDecimal("total_loads_day"),
                    rs.getBigDecimal("total_loads_week"),
                    rs.getInt("count_loads_day"));
            return new FundAcceptanceResult(
                    rs.getLong("load_id"),
                    rs.getLong("customer_id"),
                    rs.getBoolean("accepted"),
                    stats);
        }, loadId);
    }

    private FundRequest mapFundRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        FundRequest request = new FundRequest();
        request.setMessageId(rs.getLong("message_id"));
        request.setId(rs.getLong("load_id"));
        request.setCustomerId(rs.getLong("customer_id"));
        request.setAmount(rs.getBigDecimal("load_amount"));
        request.setCurrency(Currency.getInstance(rs.getString("load_currency")));
        request.setPayload(rs.getString("payload"));
        return request;
    }

    private UUID loadCustomerLockToken(long customerId) {
        return jdbcTemplate.queryForObject(
                "SELECT lock_token FROM customer_state WHERE customer_id = ?", UUID.class, customerId);
    }
}
