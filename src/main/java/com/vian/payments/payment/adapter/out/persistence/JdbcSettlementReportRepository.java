package com.vian.payments.payment.adapter.out.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.vian.payments.payment.application.SettlementDay;
import com.vian.payments.payment.port.out.SettlementReportRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcSettlementReportRepository implements SettlementReportRepository {

    private static final String DAILY_REPORT = """
            WITH daily AS (
                SELECT CAST(created_at AS DATE) AS settled_on,
                       currency,
                       SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END) AS succeeded,
                       SUM(CASE WHEN status = 'FAILED'    THEN 1 ELSE 0 END) AS failed,
                       SUM(CASE WHEN status = 'IN_DOUBT'  THEN 1 ELSE 0 END) AS in_doubt,
                       SUM(CASE WHEN status = 'SUCCEEDED' THEN amount ELSE 0 END) AS settled_amount
                FROM payments
                WHERE created_at >= ? AND created_at < ?
                GROUP BY CAST(created_at AS DATE), currency
            )
            SELECT settled_on,
                   currency,
                   succeeded,
                   failed,
                   in_doubt,
                   settled_amount,
                   SUM(settled_amount) OVER (PARTITION BY currency ORDER BY settled_on) AS running_total
            FROM daily
            ORDER BY currency, settled_on
            """;

    private static final RowMapper<SettlementDay> MAPPER = (rs, rowNum) -> new SettlementDay(
            rs.getObject("settled_on", LocalDate.class),
            rs.getString("currency"),
            rs.getInt("succeeded"),
            rs.getInt("failed"),
            rs.getInt("in_doubt"),
            rs.getBigDecimal("settled_amount"),
            rs.getBigDecimal("running_total"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcSettlementReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<SettlementDay> findDailySettlements(OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
        return jdbcTemplate.query(DAILY_REPORT, MAPPER, fromInclusive, toExclusive);
    }
}
