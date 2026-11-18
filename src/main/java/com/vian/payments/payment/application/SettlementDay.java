package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementDay(
        LocalDate day,
        String currency,
        int succeeded,
        int failed,
        int inDoubt,
        BigDecimal settledAmount,
        BigDecimal runningTotal) {
}
