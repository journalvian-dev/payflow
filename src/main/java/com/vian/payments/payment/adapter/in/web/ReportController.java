package com.vian.payments.payment.adapter.in.web;

import java.time.LocalDate;

import com.vian.payments.payment.application.SettlementReport;
import com.vian.payments.payment.port.in.GetSettlementReportUseCase;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final GetSettlementReportUseCase getSettlementReportUseCase;

    public ReportController(GetSettlementReportUseCase getSettlementReportUseCase) {
        this.getSettlementReportUseCase = getSettlementReportUseCase;
    }

    @GetMapping("/settlements")
    public SettlementReport settlements(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return getSettlementReportUseCase.getReport(from, to);
    }
}
