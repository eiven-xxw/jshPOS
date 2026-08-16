package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.RefundStatus;
import com.jingshanghui.pos.payment.domain.RefundRules.RefundLine;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原单退款金额、数量占额和状态机固定回归。 */
class RefundRulesTest {

    private static final String LINE = "01K2A000000000000000000041";

    @Test
    void validatesExactLineAmountAndQuantityWithoutFloatingPoint() {
        RefundRules.validateLines(List.of(new RefundLine(LINE, new BigDecimal("1.250000"), 1_299)), 1_299);
        RefundRules.requireAmountAvailable(10_000, 2_000, 8_000);
        RefundRules.requireQuantityAvailable(new BigDecimal("5.000000"), new BigDecimal("1.250000"),
            new BigDecimal("3.750000"));
    }

    @Test
    void rejectsEmptyDuplicateOverflowAndMismatchedRefundLines() {
        assertInvalid(() -> RefundRules.validateLines(List.of(), 100), "REF-LINE-001");
        assertInvalid(() -> RefundRules.validateLines(List.of(
            new RefundLine(LINE, BigDecimal.ONE, 50), new RefundLine(LINE, BigDecimal.ONE, 50)), 100),
            "REF-LINE-002");
        assertInvalid(() -> RefundRules.validateLines(List.of(new RefundLine(LINE, new BigDecimal("0.0000001"), 100)), 100),
            "REF-LINE-002");
        assertInvalid(() -> RefundRules.validateLines(List.of(new RefundLine(LINE, BigDecimal.ONE, 99)), 100),
            "REF-AMOUNT-002");
        assertInvalid(() -> RefundRules.validateLines(List.of(new RefundLine(LINE, BigDecimal.ONE, -1)), 1),
            "REF-LINE-002");
    }

    @Test
    void enforcesCumulativeMoneyAndQuantityCaps() {
        assertInvalid(() -> RefundRules.requireAmountAvailable(10_000, 9_000, 1_001), "REF-LIMIT-001");
        assertInvalid(() -> RefundRules.requireAmountAvailable(10_000, -1, 1), "REF-LIMIT-001");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ONE, BigDecimal.ZERO,
            new BigDecimal("1.000001")), "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(null, BigDecimal.ZERO, BigDecimal.ONE),
            "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ONE, null, BigDecimal.ONE),
            "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ONE, BigDecimal.ZERO, null),
            "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE),
            "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ONE, new BigDecimal("-0.1"),
            BigDecimal.ONE), "REF-LIMIT-002");
        assertInvalid(() -> RefundRules.requireQuantityAvailable(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO),
            "REF-LIMIT-002");
    }

    @Test
    void processingUnknownAndSucceededReserveCapacity() {
        assertThat(RefundRules.reserves(RefundStatus.PROCESSING)).isTrue();
        assertThat(RefundRules.reserves(RefundStatus.UNKNOWN)).isTrue();
        assertThat(RefundRules.reserves(RefundStatus.SUCCEEDED)).isTrue();
        assertThat(RefundRules.reserves(RefundStatus.FAILED)).isFalse();
    }

    @Test
    void successAndTerminalStatesCannotRegress() {
        assertThat(RefundRules.merge(RefundStatus.UNKNOWN, RefundStatus.PROCESSING)).isEqualTo(RefundStatus.UNKNOWN);
        assertThat(RefundRules.merge(RefundStatus.UNKNOWN, RefundStatus.SUCCEEDED)).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(RefundRules.merge(RefundStatus.SUCCEEDED, RefundStatus.FAILED)).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(RefundRules.merge(RefundStatus.FAILED, RefundStatus.SUCCEEDED)).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(RefundRules.merge(RefundStatus.CANCELLED, RefundStatus.PROCESSING)).isEqualTo(RefundStatus.CANCELLED);
    }

    @Test
    void onlyDeclaredRefundTransitionsAreLegal() {
        RefundRules.requireTransition(RefundStatus.CREATED, RefundStatus.PENDING_APPROVAL);
        RefundRules.requireTransition(RefundStatus.PENDING_APPROVAL, RefundStatus.PROCESSING);
        RefundRules.requireTransition(RefundStatus.PROCESSING, RefundStatus.UNKNOWN);
        RefundRules.requireTransition(RefundStatus.UNKNOWN, RefundStatus.SUCCEEDED);
        assertInvalid(() -> RefundRules.requireTransition(RefundStatus.PENDING_APPROVAL, RefundStatus.SUCCEEDED),
            "REF-STATE-001");
        assertInvalid(() -> RefundRules.requireTransition(RefundStatus.SUCCEEDED, RefundStatus.FAILED),
            "REF-STATE-001");
    }

    @Test
    void exhaustivelyChecksEveryRefundStatePair() {
        java.util.Set<String> legal = java.util.Set.of(
            "CREATED>PENDING_APPROVAL", "CREATED>PROCESSING", "CREATED>CANCELLED",
            "PENDING_APPROVAL>PROCESSING", "PENDING_APPROVAL>CANCELLED",
            "PROCESSING>UNKNOWN", "PROCESSING>SUCCEEDED", "PROCESSING>FAILED",
            "PROCESSING>CANCELLED", "PROCESSING>CLOSED",
            "UNKNOWN>SUCCEEDED", "UNKNOWN>FAILED", "UNKNOWN>CANCELLED", "UNKNOWN>CLOSED");
        for (RefundStatus from : RefundStatus.values()) {
            for (RefundStatus to : RefundStatus.values()) {
                String edge = from.name() + ">" + to.name();
                if (legal.contains(edge)) {
                    RefundRules.requireTransition(from, to);
                } else {
                    assertInvalid(() -> RefundRules.requireTransition(from, to), "REF-STATE-001");
                }
            }
        }
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(ServiceException.class).hasMessageContaining(code);
    }
}
