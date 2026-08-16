package com.jingshanghui.pos.procurement.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖采购数量、换算、超收、退货和状态机 P0 不变量。 */
class ProcurementRulesTest {

    @Test
    void validatesIdsTextAndSupplierStates() {
        assertThat(ProcurementRules.ulid("01K2A000000000000000000001", "id")).hasSize(26);
        assertThat(ProcurementRules.text(" name ", 16, "PUR-T")).isEqualTo("name");
        assertThat(ProcurementRules.supplierState("ACTIVE")).isEqualTo("ACTIVE");
        assertThat(ProcurementRules.supplierState("SUSPENDED")).isEqualTo("SUSPENDED");
        assertThat(ProcurementRules.supplierState("BLOCKED")).isEqualTo("BLOCKED");
        assertThatThrownBy(() -> ProcurementRules.ulid("bad", "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.ulid(null, "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.text(null, 5, "PUR-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.text("", 5, "PUR-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.text("123456", 5, "PUR-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.supplierState("DELETED")).isInstanceOf(ServiceException.class);
    }

    @Test
    void normalizesAndExactlyConvertsQuantity() {
        assertThat(ProcurementRules.quantity(new BigDecimal("2.5"), "qty")).isEqualByComparingTo("2.500000");
        assertThat(ProcurementRules.toBaseQuantity(new BigDecimal("2.5"), 12, 1))
            .isEqualByComparingTo("30.000000");
        assertThat(ProcurementRules.toBaseQuantity(new BigDecimal("1"), 1, 2))
            .isEqualByComparingTo("0.500000");
        assertThatThrownBy(() -> ProcurementRules.quantity(null, "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.quantity(BigDecimal.ZERO, "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.quantity(new BigDecimal("-1"), "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.quantity(new BigDecimal("1.0000001"), "qty"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.toBaseQuantity(BigDecimal.ONE, 0, 1))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.toBaseQuantity(BigDecimal.ONE, 1, 0))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.toBaseQuantity(BigDecimal.ONE, 1, 7))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PUR-UNIT-002");
    }

    @Test
    void validatesMoneyTaxAndTolerance() {
        assertThat(ProcurementRules.money(0)).isZero();
        assertThat(ProcurementRules.taxRate(0)).isZero();
        assertThat(ProcurementRules.taxRate(10000)).isEqualTo(10000);
        assertThat(ProcurementRules.tolerance(0)).isZero();
        assertThat(ProcurementRules.tolerance(1000)).isEqualTo(1000);
        assertThatThrownBy(() -> ProcurementRules.money(-1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.taxRate(-1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.taxRate(10001)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.tolerance(-1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.tolerance(1001)).isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesReceiptAndReturnCaps() {
        ProcurementRules.requireWithinReceiptLimit(new BigDecimal("10"), new BigDecimal("5"),
            new BigDecimal("5"), 0);
        ProcurementRules.requireWithinReceiptLimit(new BigDecimal("10"), new BigDecimal("10"),
            new BigDecimal("1"), 1000);
        assertThatThrownBy(() -> ProcurementRules.requireWithinReceiptLimit(new BigDecimal("10"),
            new BigDecimal("10"), new BigDecimal("1.000001"), 1000)).isInstanceOf(ServiceException.class);
        ProcurementRules.requireWithinReturnLimit(new BigDecimal("10"), new BigDecimal("5"),
            new BigDecimal("5"));
        assertThatThrownBy(() -> ProcurementRules.requireWithinReturnLimit(new BigDecimal("10"),
            new BigDecimal("5"), new BigDecimal("5.000001"))).isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesProcurementStates() {
        ProcurementRules.requireDraft("DRAFT");
        ProcurementRules.requireSubmitted("SUBMITTED");
        ProcurementRules.requireReceivable("APPROVED");
        ProcurementRules.requireReceivable("PARTIALLY_RECEIVED");
        ProcurementRules.requireClosable("APPROVED");
        ProcurementRules.requireClosable("PARTIALLY_RECEIVED");
        ProcurementRules.requireClosable("RECEIVED");
        assertThatThrownBy(() -> ProcurementRules.requireDraft("APPROVED")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.requireSubmitted("DRAFT")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.requireReceivable("DRAFT")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ProcurementRules.requireClosable("DRAFT")).isInstanceOf(ServiceException.class);
    }
}
