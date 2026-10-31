package com.v.limiter.model;

import java.util.UUID;

public record ClaimedFund(
        FundRequest fundRequest,
        UUID claimId,
        CustomerLoadStats customerLoadStats) {
}
