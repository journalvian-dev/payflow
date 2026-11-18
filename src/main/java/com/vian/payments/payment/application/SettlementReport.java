package com.vian.payments.payment.application;

import java.time.LocalDate;
import java.util.List;

public record SettlementReport(
        LocalDate from,
        LocalDate to,
        List<SettlementDay> days,
        List<CurrencyTotal> totals) {
}
