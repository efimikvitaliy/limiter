package com.v.limiter.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerLoadStats(
        Instant lastLoadTimestamp,
        BigDecimal totalLoadsDay,
        BigDecimal totalLoadsWeek,
        int countLoadsDay) {
}
