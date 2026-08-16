package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.AttemptStatus;
import com.jingshanghui.pos.payment.domain.PaymentStates.PaymentStatus;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 支付状态机、不变量与边界条件固定回归。 */
class PaymentRulesTest {

    @Test
    void validatesStableIdentifiersAndExactMoneyBoundaries() {
        assertThat(PaymentRules.requireUlid("01K2A000000000000000000001", "paymentId")).hasSize(26);
        assertThat(PaymentRules.requireIdempotencyKey("intent:tenant-a:0001")).contains("tenant-a");
        assertThat(PaymentRules.requireProviderCode("LAKALA_V1")).isEqualTo("LAKALA_V1");
        assertThat(PaymentRules.requireCurrency("CNY")).isEqualTo("CNY");
        assertThat(PaymentRules.requireHash("a".repeat(64))).hasSize(64);
        assertThat(PaymentRules.requirePositiveAmount(9_007_199_254_740_991L, "amount")).isPositive();
    }

    @Test
    void rejectsMalformedIdentifiersHashesCurrencyAndAmounts() {
        assertInvalid(() -> PaymentRules.requireUlid(null, "id"), "PAY-ID-001");
        assertInvalid(() -> PaymentRules.requireUlid("uuid", "id"), "PAY-ID-001");
        assertInvalid(() -> PaymentRules.requireIdempotencyKey("short"), "PAY-IDEM-001");
        assertInvalid(() -> PaymentRules.requireIdempotencyKey(null), "PAY-IDEM-001");
        assertInvalid(() -> PaymentRules.requireProviderCode("a"), "PAY-PROVIDER-001");
        assertInvalid(() -> PaymentRules.requireProviderCode(null), "PAY-PROVIDER-001");
        assertInvalid(() -> PaymentRules.requireCurrency("cny"), "PAY-CURRENCY-001");
        assertInvalid(() -> PaymentRules.requireCurrency(null), "PAY-CURRENCY-001");
        assertInvalid(() -> PaymentRules.requireHash("A".repeat(64)), "PAY-HASH-002");
        assertInvalid(() -> PaymentRules.requireHash(null), "PAY-HASH-002");
        assertInvalid(() -> PaymentRules.requirePositiveAmount(0, "amount"), "PAY-AMOUNT-001");
        assertInvalid(() -> PaymentRules.requirePositiveAmount(9_007_199_254_740_992L, "amount"), "PAY-AMOUNT-001");
    }

    @Test
    void explicitAttemptOnlyStartsFromCreatedOrFailedAndHasHardBudget() {
        PaymentRules.requireNewAttemptAllowed(PaymentStatus.CREATED, 0);
        PaymentRules.requireNewAttemptAllowed(PaymentStatus.FAILED, 7);
        assertInvalid(() -> PaymentRules.requireNewAttemptAllowed(PaymentStatus.UNKNOWN, 1), "PAY-ATTEMPT-001");
        assertInvalid(() -> PaymentRules.requireNewAttemptAllowed(PaymentStatus.CREATED, 8), "PAY-ATTEMPT-002");
    }

    @Test
    void unknownConvergesButSuccessfulFundsNeverRegress() {
        assertThat(PaymentRules.merge(PaymentStatus.PROCESSING, AttemptStatus.UNKNOWN))
            .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PaymentRules.merge(PaymentStatus.UNKNOWN, AttemptStatus.PROCESSING))
            .isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(PaymentRules.merge(PaymentStatus.UNKNOWN, AttemptStatus.SUCCEEDED))
            .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(PaymentRules.merge(PaymentStatus.SUCCEEDED, AttemptStatus.FAILED))
            .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(PaymentRules.merge(PaymentStatus.PARTIALLY_REFUNDED, AttemptStatus.UNKNOWN))
            .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentRules.merge(PaymentStatus.REFUNDED, AttemptStatus.FAILED))
            .isEqualTo(PaymentStatus.REFUNDED);
        assertThat(PaymentRules.merge(PaymentStatus.PROCESSING, AttemptStatus.CREATED))
            .isEqualTo(PaymentStatus.PROCESSING);
        assertThat(PaymentRules.merge(PaymentStatus.PROCESSING, AttemptStatus.FAILED))
            .isEqualTo(PaymentStatus.FAILED);
        assertThat(PaymentRules.merge(PaymentStatus.CANCELLED, AttemptStatus.SUCCEEDED))
            .isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(PaymentRules.merge(PaymentStatus.CLOSED, AttemptStatus.UNKNOWN))
            .isEqualTo(PaymentStatus.CLOSED);
    }

    @Test
    void attemptMergeIsArrivalOrderIndependentForStrongFacts() {
        assertThat(PaymentRules.mergeAttempt(AttemptStatus.UNKNOWN, AttemptStatus.PROCESSING))
            .isEqualTo(AttemptStatus.UNKNOWN);
        assertThat(PaymentRules.mergeAttempt(AttemptStatus.UNKNOWN, AttemptStatus.SUCCEEDED))
            .isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(PaymentRules.mergeAttempt(AttemptStatus.SUCCEEDED, AttemptStatus.FAILED))
            .isEqualTo(AttemptStatus.SUCCEEDED);
        assertThat(PaymentRules.mergeAttempt(AttemptStatus.FAILED, AttemptStatus.PROCESSING))
            .isEqualTo(AttemptStatus.FAILED);
        assertThat(PaymentRules.mergeAttempt(AttemptStatus.PROCESSING, AttemptStatus.CANCELLED))
            .isEqualTo(AttemptStatus.CANCELLED);
        for (AttemptStatus current : AttemptStatus.values()) {
            for (AttemptStatus observed : AttemptStatus.values()) {
                assertThat(PaymentRules.mergeAttempt(current, observed)).isNotNull();
            }
        }
    }

    @Test
    void everyPaymentAndAttemptObservationPairHasDeterministicResult() {
        for (PaymentStatus current : PaymentStatus.values()) {
            for (AttemptStatus observed : AttemptStatus.values()) {
                assertThat(PaymentRules.merge(current, observed)).isNotNull();
            }
        }
    }

    @Test
    void successfulRefundAggregateCannotExceedPaidAmount() {
        assertThat(PaymentRules.isConfirmedFunds(PaymentStatus.SUCCEEDED)).isTrue();
        assertThat(PaymentRules.isConfirmedFunds(PaymentStatus.UNKNOWN)).isFalse();
        assertThat(PaymentRules.afterSuccessfulRefund(10_000, 0)).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(PaymentRules.afterSuccessfulRefund(10_000, 1)).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(PaymentRules.afterSuccessfulRefund(10_000, 10_000)).isEqualTo(PaymentStatus.REFUNDED);
        assertInvalid(() -> PaymentRules.afterSuccessfulRefund(10_000, -1), "REF-AMOUNT-003");
        assertInvalid(() -> PaymentRules.afterSuccessfulRefund(10_000, 10_001), "REF-AMOUNT-003");
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(ServiceException.class).hasMessageContaining(code);
    }
}
