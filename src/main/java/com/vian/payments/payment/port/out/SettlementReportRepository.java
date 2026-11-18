package com.vian.payments.payment.port.out;

import java.time.OffsetDateTime;
import java.util.List;

import com.vian.payments.payment.application.SettlementDay;

public interface SettlementReportRepository {

    /**
     * One row per day and currency, with the settled amount accumulated per currency.
     * The range is half-open: {@code fromInclusive} up to but excluding {@code toExclusive}.
     */
    List<SettlementDay> findDailySettlements(OffsetDateTime fromInclusive, OffsetDateTime toExclusive);
}
