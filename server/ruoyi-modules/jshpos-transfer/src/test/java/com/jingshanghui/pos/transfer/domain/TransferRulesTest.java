package com.jingshanghui.pos.transfer.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.jingshanghui.pos.transfer.domain.TransferStates.Status.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 固定覆盖调拨状态、精度、差异和在途恒等式。 */
class TransferRulesTest {
    private static final String A = "01K2A000000000000000000001";
    private static final String B = "01K2A000000000000000000002";

    @Test
    void acceptsCanonicalIdsTextQuantityAndDistinctRoute() {
        TransferRules.ulid(A, "id");
        assertThat(TransferRules.text(" reason ", 16, "X")).isEqualTo("reason");
        assertThat(TransferRules.quantity(new BigDecimal("1.25"), "qty"))
            .isEqualByComparingTo("1.250000");
        TransferRules.distinctWarehouses(A, B);
    }

    @Test
    void rejectsInvalidIdsTextQuantityAndSameWarehouse() {
        assertThatThrownBy(() -> TransferRules.ulid("bad", "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.ulid(null, "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.text("", 3, "X")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.text("long", 3, "X")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.quantity(null, "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.quantity(BigDecimal.ZERO, "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.quantity(new BigDecimal("1.0000001"), "qty"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.quantity(new BigDecimal("10000000000000"), "qty"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.distinctWarehouses(A, A)).isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesTransitionsAndReceivableStates() {
        assertThat(TransferRules.transition(DRAFT, DRAFT, SUBMITTED)).isEqualTo(SUBMITTED);
        TransferRules.receivable(IN_TRANSIT);
        TransferRules.receivable(PARTIALLY_RECEIVED);
        assertThatThrownBy(() -> TransferRules.transition(APPROVED, DRAFT, SUBMITTED))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-STATE-001");
        assertThatThrownBy(() -> TransferRules.receivable(APPROVED))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-STATE-002");
    }

    @Test
    void preventsOverReceiptAndPreservesTransitEquation() {
        TransferRules.withinRemaining(new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("1"),
            new BigDecimal("5"));
        assertThat(TransferRules.openTransit(new BigDecimal("10"), new BigDecimal("4"), new BigDecimal("1")))
            .isEqualByComparingTo("5.000000");
        assertThatThrownBy(() -> TransferRules.withinRemaining(new BigDecimal("10"), new BigDecimal("4"),
            new BigDecimal("1"), new BigDecimal("6"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> TransferRules.openTransit(new BigDecimal("1"), new BigDecimal("2"),
            BigDecimal.ZERO)).isInstanceOf(ServiceException.class).hasMessageContaining("TRF-TRANSIT-001");
    }

    @Test
    void restrictsDifferenceReasons() {
        assertThat(TransferRules.differenceReason("SHORTAGE")).isEqualTo(TransferStates.DifferenceReason.SHORTAGE);
        assertThatThrownBy(() -> TransferRules.differenceReason("OTHER"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-DIFF-001");
    }
}
