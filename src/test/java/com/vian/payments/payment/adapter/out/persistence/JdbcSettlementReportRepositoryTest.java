package com.vian.payments.payment.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import com.vian.payments.payment.application.SettlementDay;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class JdbcSettlementReportRepositoryTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-11-04T00:00:00Z");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcSettlementReportRepository repository;

    @BeforeEach
    void setUp() {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbcTemplate.update("DELETE FROM payments");
        repository = new JdbcSettlementReportRepository(jdbcTemplate);
    }

    @Test
    void groupsByDayAndCurrencyWithRunningTotalPerCurrency() {
        insert("IDR", "SUCCEEDED", "100000.00", "2026-11-01T09:00:00Z");
        insert("IDR", "SUCCEEDED", "50000.00", "2026-11-01T11:00:00Z");
        insert("IDR", "FAILED", "70000.00", "2026-11-01T12:00:00Z");
        insert("IDR", "SUCCEEDED", "200000.00", "2026-11-02T09:00:00Z");
        insert("IDR", "IN_DOUBT", "30000.00", "2026-11-02T09:30:00Z");
        insert("USD", "SUCCEEDED", "40.00", "2026-11-01T09:00:00Z");

        List<SettlementDay> rows = repository.findDailySettlements(FROM, TO);

        assertThat(rows).hasSize(3);

        SettlementDay firstIdr = rows.get(0);
        assertThat(firstIdr.day()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(firstIdr.currency()).isEqualTo("IDR");
        assertThat(firstIdr.succeeded()).isEqualTo(2);
        assertThat(firstIdr.failed()).isEqualTo(1);
        assertThat(firstIdr.settledAmount()).isEqualByComparingTo("150000.00");
        assertThat(firstIdr.runningTotal()).isEqualByComparingTo("150000.00");

        SettlementDay secondIdr = rows.get(1);
        assertThat(secondIdr.day()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(secondIdr.succeeded()).isEqualTo(1);
        assertThat(secondIdr.inDoubt()).isEqualTo(1);
        assertThat(secondIdr.settledAmount()).isEqualByComparingTo("200000.00");
        assertThat(secondIdr.runningTotal()).isEqualByComparingTo("350000.00");

        SettlementDay usd = rows.get(2);
        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.runningTotal()).isEqualByComparingTo("40.00");
    }

    @Test
    void excludesTheUpperBound() {
        insert("IDR", "SUCCEEDED", "100000.00", "2026-11-03T23:59:59Z");
        insert("IDR", "SUCCEEDED", "999999.00", "2026-11-04T00:00:00Z");

        List<SettlementDay> rows = repository.findDailySettlements(FROM, TO);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).settledAmount()).isEqualByComparingTo("100000.00");
    }

    private void insert(String currency, String status, String amount, String createdAt) {
        OffsetDateTime created = OffsetDateTime.parse(createdAt);
        jdbcTemplate.update("""
                INSERT INTO payments (id, reference, customer_id, amount, currency, status, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)
                """,
                UUID.randomUUID(), "REF-" + UUID.randomUUID(), "CUST-1",
                new BigDecimal(amount), currency, status, created, created);
    }
}
