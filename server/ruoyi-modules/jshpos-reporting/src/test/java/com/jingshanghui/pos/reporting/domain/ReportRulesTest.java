package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/** 报表口径、精度、日期和导出白名单领域回归。 */
class ReportRulesTest {
    @Test void acceptsIdentifiersCurrencyOwnerAndConservedMoney() {
        assertThat(ReportRules.requireUlid("01ARZ3NDEKTSV4RRFFQ69G5FAV", "X")).hasSize(26);
        assertThat(ReportRules.requireSha256("a".repeat(64), "X")).hasSize(64);
        assertThat(ReportRules.requireCode("store:1", "X")).isEqualTo("store:1");
        assertThat(ReportRules.requireCurrency("CNY")).isEqualTo("CNY");
        ReportRules.requireOwnerFamily("ORDER", "SALES");
        ReportRules.requireOwnerFamily("COSTING", "INVENTORY_COST");
        ReportRules.requireSalesConservation(1000, 100, 10, 910);
    }

    @Test void rejectsIdentifiersCurrencyOwnerAndMoneyViolations() {
        assertThatThrownBy(() -> ReportRules.requireUlid("bad", "X")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireSha256("A".repeat(64), "X")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireCode("../x", "X")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireCurrency("USD")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireOwnerFamily("PAYMENT", "SALES")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireOwnerFamily("ORDER", "UNKNOWN")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireSalesConservation(100, 5, 0, 96)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireSalesConservation(Long.MAX_VALUE, -1, 0, 0))
            .isInstanceOf(ServiceException.class);
    }

    @Test void enforcesExactDecimalAndDateRange() {
        assertThat(ReportRules.exactDecimal(new BigDecimal("1.25"), "X")).isEqualByComparingTo("1.250000");
        assertThatThrownBy(() -> ReportRules.exactDecimal(new BigDecimal("1.0000001"), "X"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.exactDecimal(null, "X")).isInstanceOf(ServiceException.class);
        LocalDate day = LocalDate.of(2026, 8, 17);
        ReportRules.requireDateRange(day, day.plusDays(30));
        assertThatThrownBy(() -> ReportRules.requireDateRange(day, day.minusDays(1))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireDateRange(day, day.plusDays(31))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireDateRange(null, day)).isInstanceOf(ServiceException.class);
    }

    @Test void validatesExportFieldsAndApprovalThreshold() {
        Set<String> sales = ReportRules.requireExportFields("SALES_DAILY", Set.of("businessDate", "grossMinor"));
        assertThat(sales).containsExactlyInAnyOrder("businessDate", "grossMinor");
        assertThat(ReportRules.requiresApproval("SALES_DAILY", 10_001, sales)).isTrue();
        assertThat(ReportRules.requiresApproval("SALES_DAILY", 10_000, sales)).isFalse();
        assertThat(ReportRules.requiresApproval("INVENTORY_COST_DAILY", 1, Set.of("businessDate"))).isTrue();
        assertThat(ReportRules.requiresApproval("SALES_DAILY", 1, Set.of("businessDate", "purchaseCost"))).isTrue();
        assertThatThrownBy(() -> ReportRules.requireExportFields("UNKNOWN", Set.of("x")))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireExportFields("SALES_DAILY", Set.of("password")))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReportRules.requireExportFields("SALES_DAILY", Set.of()))
            .isInstanceOf(ServiceException.class);
    }

    @Test void neutralizesCsvFormulaAndLineBreaks() {
        assertThat(ReportRules.safeCsvText("=1+1\r\nnext")).isEqualTo("'=1+1 next");
        assertThat(ReportRules.safeCsvText("+SUM(A1)")).startsWith("'");
        assertThat(ReportRules.safeCsvText("-2")).startsWith("'");
        assertThat(ReportRules.safeCsvText("@cmd")).startsWith("'");
        assertThat(ReportRules.safeCsvText("\tcmd")).startsWith("'");
        assertThat(ReportRules.safeCsvText("safe")).isEqualTo("safe");
        assertThat(ReportRules.safeCsvText(null)).isEmpty();
    }
}
