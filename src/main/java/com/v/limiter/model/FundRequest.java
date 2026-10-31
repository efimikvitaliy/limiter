package com.v.limiter.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Currency;

public class FundRequest {
    private Long messageId;
    private Long id;

    @JsonProperty("customer_id")
    private Long customerId;

    private BigDecimal amount;

    private Currency currency;

    private String time;
    private String payload;

    public FundRequest() {
    }

    public Long getId() {
        return id;
    }

    public Long getMessageId() {
        return messageId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    @JsonProperty("load_amount")
    public void setLoadAmount(String loadAmount) {
        if (loadAmount == null || loadAmount.length() < 2) {
            throw new IllegalArgumentException("load_amount must include a currency symbol and amount");
        }

        String currencyCode = switch (loadAmount.charAt(0)) {
            case '$' -> "USD";
            case '€' -> "EUR";
            case '£' -> "GBP";
            case '¥' -> "JPY";
            default -> throw new IllegalArgumentException("Unsupported load_amount currency symbol");
        };

        this.amount = new BigDecimal(loadAmount.substring(1));
        this.currency = Currency.getInstance(currencyCode);
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

}
