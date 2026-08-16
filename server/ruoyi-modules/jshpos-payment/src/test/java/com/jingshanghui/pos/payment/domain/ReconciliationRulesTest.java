package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.DifferenceType;
import com.jingshanghui.pos.payment.domain.PaymentStates.ReconciliationStatus;
import com.jingshanghui.pos.payment.domain.ReconciliationRules.Fact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 双源对账差异分类与人工处置状态机固定回归。 */
class ReconciliationRulesTest {

    @Test
    void classifiesMissingAndAllMismatchedDimensionsDeterministically() {
        Fact same = new Fact("txn-1", "PAYMENT", "SUCCEEDED", 1_299, "CNY");
        assertThat(ReconciliationRules.compare(null, same)).containsExactly(DifferenceType.PROVIDER_ONLY);
        assertThat(ReconciliationRules.compare(same, null)).containsExactly(DifferenceType.INTERNAL_ONLY);
        assertThat(ReconciliationRules.compare(same, same)).isEmpty();
        Fact different = new Fact("txn-1", "REFUND", "FAILED", 1_300, "USD");
        assertThat(ReconciliationRules.compare(same, different)).containsExactly(
            DifferenceType.AMOUNT_MISMATCH, DifferenceType.CURRENCY_MISMATCH,
            DifferenceType.STATUS_MISMATCH, DifferenceType.REFUND_MISMATCH);
    }

    @Test
    void enforcesFourEyesCaseLifecycle() {
        ReconciliationRules.requireTransition(ReconciliationStatus.OPEN, ReconciliationStatus.INVESTIGATING);
        ReconciliationRules.requireTransition(ReconciliationStatus.OPEN, ReconciliationStatus.WAITING_PROVIDER);
        ReconciliationRules.requireTransition(ReconciliationStatus.INVESTIGATING, ReconciliationStatus.RESOLVED);
        ReconciliationRules.requireTransition(ReconciliationStatus.WAITING_PROVIDER, ReconciliationStatus.RESOLVED);
        ReconciliationRules.requireTransition(ReconciliationStatus.RESOLVED, ReconciliationStatus.APPROVED);
        ReconciliationRules.requireTransition(ReconciliationStatus.APPROVED, ReconciliationStatus.CLOSED);
        assertThatThrownBy(() -> ReconciliationRules.requireTransition(ReconciliationStatus.OPEN,
            ReconciliationStatus.CLOSED)).isInstanceOf(IllegalStateException.class).hasMessageContaining("REC-STATE-001");
        assertThatThrownBy(() -> ReconciliationRules.requireTransition(ReconciliationStatus.CLOSED,
            ReconciliationStatus.OPEN)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exhaustivelyChecksEveryReconciliationStatePair() {
        java.util.Set<String> legal = java.util.Set.of("OPEN>INVESTIGATING", "OPEN>WAITING_PROVIDER",
            "INVESTIGATING>RESOLVED", "WAITING_PROVIDER>RESOLVED", "RESOLVED>APPROVED", "APPROVED>CLOSED");
        for (ReconciliationStatus from : ReconciliationStatus.values()) {
            for (ReconciliationStatus to : ReconciliationStatus.values()) {
                String edge = from.name() + ">" + to.name();
                if (legal.contains(edge)) {
                    ReconciliationRules.requireTransition(from, to);
                } else {
                    assertThatThrownBy(() -> ReconciliationRules.requireTransition(from, to))
                        .isInstanceOf(IllegalStateException.class);
                }
            }
        }
    }
}
