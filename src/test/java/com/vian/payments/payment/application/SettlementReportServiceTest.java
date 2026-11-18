package com.vian.payments.payment.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementReportServiceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 3);

    @Test
    void totalsAreGroupedPerCurrency() {
        SettlementReportService service = new SettlementReportService((from, to) -> List.of(
                day("2026-11-01", "IDR", 2, 0, 0, "300000.00", "300000.00"),
                day("2026-11-02", "IDR", 1, 1, 0, "500000.00", "800000.00"),
                day("2026-11-01", "USD", 1, 0, 0, "50.00", "50.00")));

        SettlementReport report = service.getReport(FROM, TO);

        assertThat(report.days()).hasSize(3);
        assertThat(report.totals()).hasSize(2);

        CurrencyTotal idr = total(report, "IDR");
        assertThat(idr.succeeded()).isEqualTo(3);
        assertThat(idr.failed()).isEqualTo(1);
        assertThat(idr.settledAmount()).isEqualByComparingTo("800000.00");
        assertThat(idr.bestDay()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(idr.bestDayAmount()).isEqualByComparingTo("500000.00");

        CurrencyTotal usd = total(report, "USD");
        assertThat(usd.settledAmount()).isEqualByComparingTo("50.00");
    }

    @Test
    void asksForTheDayAfterTheRequestedEndDate() {
        AtomicReference<OffsetDateTime> queriedFrom = new AtomicReference<>();
        AtomicReference<OffsetDateTime> queriedTo = new AtomicReference<>();

        SettlementReportService service = new SettlementReportService((from, to) -> {
            queriedFrom.set(from);
            queriedTo.set(to);
            return List.of();
        });

        service.getReport(FROM, TO);

        assertThat(queriedFrom.get()).isEqualTo(FROM.atStartOfDay().atOffset(ZoneOffset.UTC));
        assertThat(queriedTo.get()).isEqualTo(TO.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    @Test
    void rejectsAReversedRange() {
        SettlementReportService service = new SettlementReportService((from, to) -> List.of());

        assertThatThrownBy(() -> service.getReport(TO, FROM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from");
    }

    private static CurrencyTotal total(SettlementReport report, String currency) {
        return report.totals().stream()
                .filter(total -> total.currency().equals(currency))
                .findFirst()
                .orElseThrow();
    }

    private static SettlementDay day(String day, String currency, int succeeded, int failed, int inDoubt,
                                     String settled, String running) {
        return new SettlementDay(LocalDate.parse(day), currency, succeeded, failed, inDoubt,
                new BigDecimal(settled), new BigDecimal(running));
    }
}
