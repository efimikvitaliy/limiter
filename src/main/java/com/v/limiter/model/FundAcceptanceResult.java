package com.v.limiter.model;

public record FundAcceptanceResult(long id, long customerId, boolean accepted, CustomerLoadStats newStats) {
}
