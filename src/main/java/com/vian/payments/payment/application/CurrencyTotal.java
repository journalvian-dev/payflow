package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CurrencyTotal(
        String currency,
        int succeeded,
        int failed,
        int inDoubt,
        BigDecimal settledAmount,
        LocalDate bestDay,
        BigDecimal bestDayAmount) {
}
