package com.v.limiter.process;

import com.v.limiter.model.ClaimedFund;
import com.v.limiter.model.CustomerLoadStats;
import com.v.limiter.model.FundAcceptanceResult;
import com.v.limiter.model.FundRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

@Service
public class LimitsCheckingService {
    private final BigDecimal maxUsdPerDay;
    private final BigDecimal maxUsdPerWeek;
    private final int maxLoadsPerDay;
    private final CurrencyConverter currencyConverter;

    public LimitsCheckingService(
            CurrencyConverter currencyConverter,
            @Value("${limits.max-usd-per-day:5000.00}") BigDecimal maxUsdPerDay,
            @Value("${limits.max-usd-per-week:20000.00}") BigDecimal maxUsdPerWeek,
            @Value("${limits.max-loads-per-day:3}") int maxLoadsPerDay) {
        this.currencyConverter = currencyConverter;
        this.maxUsdPerDay = maxUsdPerDay;
        this.maxUsdPerWeek = maxUsdPerWeek;
        this.maxLoadsPerDay = maxLoadsPerDay;
    }

    public FundAcceptanceResult acceptFund(ClaimedFund claimedFund) {
        CustomerLoadStats newStats = calculateNewStats(claimedFund);
        boolean accepted = newStats.totalLoadsDay().compareTo(maxUsdPerDay) <= 0
                && newStats.totalLoadsWeek().compareTo(maxUsdPerWeek) <= 0
                && newStats.countLoadsDay() <= maxLoadsPerDay;
        return new FundAcceptanceResult(
                claimedFund.fundRequest().getId(),
                claimedFund.fundRequest().getCustomerId(),
                accepted,
                accepted ? newStats : claimedFund.customerLoadStats());
    }

    public CustomerLoadStats calculateNewStats(ClaimedFund claimedFund) {
        FundRequest fund = claimedFund.fundRequest();
        Instant fundTimestamp = Instant.parse(fund.getTime());
        BigDecimal amountUsd = currencyConverter.convertToUsd(fund.getAmount(), fund.getCurrency());
        CustomerLoadStats previousStats = claimedFund.customerLoadStats();

        boolean sameDay = previousStats.lastLoadTimestamp() != null
                && utcDate(previousStats.lastLoadTimestamp()).equals(utcDate(fundTimestamp));
        boolean sameWeek = previousStats.lastLoadTimestamp() != null
                && weekStart(previousStats.lastLoadTimestamp()).equals(weekStart(fundTimestamp));

        BigDecimal totalLoadsDay = sameDay
                ? previousStats.totalLoadsDay().add(amountUsd)
                : amountUsd;
        BigDecimal totalLoadsWeek = sameWeek
                ? previousStats.totalLoadsWeek().add(amountUsd)
                : amountUsd;
        int countLoadsDay = (sameDay ? previousStats.countLoadsDay() : 0) + 1;

        return new CustomerLoadStats(fundTimestamp, totalLoadsDay, totalLoadsWeek, countLoadsDay);
    }

    private LocalDate utcDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }

    private LocalDate weekStart(Instant instant) {
        return utcDate(instant).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }
}
