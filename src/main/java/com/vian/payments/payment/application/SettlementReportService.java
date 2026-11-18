package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.vian.payments.payment.port.in.GetSettlementReportUseCase;
import com.vian.payments.payment.port.out.SettlementReportRepository;
import org.springframework.stereotype.Service;

@Service
public class SettlementReportService implements GetSettlementReportUseCase {

    private final SettlementReportRepository repository;

    public SettlementReportService(SettlementReportRepository repository) {
        this.repository = repository;
    }

    @Override
    public SettlementReport getReport(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }

        List<SettlementDay> days = repository.findDailySettlements(
                from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));

        return new SettlementReport(from, to, days, totalsByCurrency(days));
    }

    private List<CurrencyTotal> totalsByCurrency(List<SettlementDay> days) {
        Map<String, List<SettlementDay>> grouped = days.stream()
                .collect(Collectors.groupingBy(
                        SettlementDay::currency,
                        LinkedHashMap::new,
                        Collectors.toList()));

        return grouped.entrySet().stream()
                .map(entry -> total(entry.getKey(), entry.getValue()))
                .toList();
    }

    private CurrencyTotal total(String currency, List<SettlementDay> days) {
        int succeeded = days.stream().mapToInt(SettlementDay::succeeded).sum();
        int failed = days.stream().mapToInt(SettlementDay::failed).sum();
        int inDoubt = days.stream().mapToInt(SettlementDay::inDoubt).sum();
        BigDecimal settled = days.stream()
                .map(SettlementDay::settledAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Optional<SettlementDay> best = days.stream()
                .max(Comparator.comparing(SettlementDay::settledAmount));

        return new CurrencyTotal(
                currency,
                succeeded,
                failed,
                inDoubt,
                settled,
                best.map(SettlementDay::day).orElse(null),
                best.map(SettlementDay::settledAmount).orElse(BigDecimal.ZERO));
    }
}
