package com.v.limiter.process;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;

@Service
public class CurrencyConverter {
    private final Map<String, BigDecimal> usdPerUnit;

    public CurrencyConverter(
            @Value("${currency.usd-per-unit.USD:1.00}") BigDecimal usdPerUsd,
            @Value("${currency.usd-per-unit.EUR:1.08}") BigDecimal usdPerEur,
            @Value("${currency.usd-per-unit.GBP:1.27}") BigDecimal usdPerGbp,
            @Value("${currency.usd-per-unit.JPY:0.0067}") BigDecimal usdPerJpy) {
        this.usdPerUnit = Map.of(
                "USD", usdPerUsd,
                "EUR", usdPerEur,
                "GBP", usdPerGbp,
                "JPY", usdPerJpy);
    }

    public BigDecimal convertToUsd(BigDecimal amount, Currency currency) {
        BigDecimal rate = usdPerUnit.get(currency.getCurrencyCode());
        if (rate == null) {
            throw new IllegalArgumentException("No USD conversion rate configured for " + currency);
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }
}
