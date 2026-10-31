package com.v.limiter.process;

import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.CustomerLoadStats;
import com.v.limiter.model.FundAcceptanceResult;
import com.v.limiter.model.FundRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitsCheckingServiceTest {
    private LimitsCheckingService service;

    @BeforeEach
    void setUp() {
        CurrencyConverter converter = new CurrencyConverter(
                new BigDecimal("1.00"),
                new BigDecimal("1.08"),
                new BigDecimal("1.27"),
                new BigDecimal("0.0067"));
        service = new LimitsCheckingService(
                converter,
                new BigDecimal("5000.00"),
                new BigDecimal("20000.00"),
                3);
    }

    @Test
    void acceptsFundAtAllConfiguredLimitsAndReturnsUpdatedStats() {
        CustomerLoadStats previous = stats("2024-01-01T10:00:00Z", "4900.00", "19900.00", 2);
        ClaimedFund fund = fund("100.00", "USD", "2024-01-01T11:00:00Z", previous);

        FundAcceptanceResult result = service.acceptFund(fund);

        assertTrue(result.accepted());
        assertEquals(1234L, result.id());
        assertEquals(5678L, result.customerId());
        assertEquals(stats("2024-01-01T11:00:00Z", "5000.00", "20000.00", 3), result.newStats());
    }

    @Test
    void rejectsDailyAmountOverLimitAndKeepsExistingStats() {
        CustomerLoadStats previous = stats("2024-01-01T10:00:00Z", "4999.99", "10000.00", 1);
        ClaimedFund fund = fund("0.02", "USD", "2024-01-01T11:00:00Z", previous);

        FundAcceptanceResult result = service.acceptFund(fund);

        assertFalse(result.accepted());
        assertEquals(previous, result.newStats());
    }

    @Test
    void rejectsWeeklyAmountOverLimit() {
        CustomerLoadStats previous = stats("2024-01-01T10:00:00Z", "100.00", "19999.99", 1);
        ClaimedFund fund = fund("0.02", "USD", "2024-01-01T11:00:00Z", previous);

        FundAcceptanceResult result = service.acceptFund(fund);

        assertFalse(result.accepted());
        assertEquals(previous, result.newStats());
    }

    @Test
    void rejectsFourthLoadInUtcDay() {
        CustomerLoadStats previous = stats("2024-01-01T10:00:00Z", "100.00", "100.00", 3);
        ClaimedFund fund = fund("1.00", "USD", "2024-01-01T11:00:00Z", previous);

        FundAcceptanceResult result = service.acceptFund(fund);

        assertFalse(result.accepted());
        assertEquals(previous, result.newStats());
    }

    @Test
    void resetsDailyAndWeeklyStatsAtUtcMondayBoundary() {
        CustomerLoadStats previous = stats("2024-01-07T23:59:00Z", "4999.00", "19999.00", 3);
        ClaimedFund fund = fund("25.00", "USD", "2024-01-08T00:01:00Z", previous);

        CustomerLoadStats newStats = service.calculateNewStats(fund);

        assertEquals(stats("2024-01-08T00:01:00Z", "25.00", "25.00", 1), newStats);
    }

    @Test
    void convertsForeignCurrencyAndUsesRequestTimestamp() {
        CustomerLoadStats previous = stats("2024-01-01T10:00:00Z", "0.00", "0.00", 0);
        ClaimedFund fund = fund("10.00", "EUR", "2024-01-01T12:00:00+01:00", previous);

        CustomerLoadStats newStats = service.calculateNewStats(fund);

        assertEquals(stats("2024-01-01T11:00:00Z", "10.80", "10.80", 1), newStats);
    }

    private ClaimedFund fund(String amount, String currencyCode, String time, CustomerLoadStats oldStats) {
        FundRequest request = new FundRequest();
        request.setId(1234L);
        request.setCustomerId(5678L);
        request.setAmount(new BigDecimal(amount));
        request.setCurrency(Currency.getInstance(currencyCode));
        request.setTime(time);
        return new ClaimedFund(request, UUID.randomUUID(), oldStats);
    }

    private CustomerLoadStats stats(String timestamp, String daily, String weekly, int count) {
        return new CustomerLoadStats(Instant.parse(timestamp), new BigDecimal(daily), new BigDecimal(weekly), count);
    }
}
