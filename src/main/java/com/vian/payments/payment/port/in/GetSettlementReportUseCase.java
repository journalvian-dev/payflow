package com.vian.payments.payment.port.in;

import java.time.LocalDate;

import com.vian.payments.payment.application.SettlementReport;

public interface GetSettlementReportUseCase {

    SettlementReport getReport(LocalDate from, LocalDate to);
}
