package com.v.limiter.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyConverterTest {
    private CurrencyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CurrencyConverter(
                new BigDecimal("1.00"),
                new BigDecimal("1.08"),
                new BigDecimal("1.27"),
                new BigDecimal("0.0067"));
    }

    @Test
    void convertsUsd() {
        assertEquals(new BigDecimal("12.34"), converter.convertToUsd(
                new BigDecimal("12.34"), Currency.getInstance("USD")));
    }

    @Test
    void convertsEur() {
        assertEquals(new BigDecimal("10.80"), converter.convertToUsd(
                new BigDecimal("10.00"), Currency.getInstance("EUR")));
    }

    @Test
    void convertsGbp() {
        assertEquals(new BigDecimal("12.70"), converter.convertToUsd(
                new BigDecimal("10.00"), Currency.getInstance("GBP")));
    }

    @Test
    void convertsJpy() {
        assertEquals(new BigDecimal("0.07"), converter.convertToUsd(
                new BigDecimal("10.00"), Currency.getInstance("JPY")));
    }

    @Test
    void roundsConvertedAmountToCents() {
        assertEquals(new BigDecimal("1.01"), converter.convertToUsd(
                new BigDecimal("1.005"), Currency.getInstance("USD")));
    }

    @Test
    void rejectsCurrencyWithoutConfiguredRate() {
        assertThrows(IllegalArgumentException.class, () -> converter.convertToUsd(
                new BigDecimal("10.00"), Currency.getInstance("CAD")));
    }
}
