package com.jingshanghui.pos.returns.domain;

import com.jingshanghui.pos.returns.domain.ReturnStates.Status;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** T2-REF-002 数量、金额、标识和状态机不变量。 */
class ReturnRulesTest {

    @Test
    void normalizesPreciseQuantityAndRejectsFloatLikeOrOversizedValues() {
        assertThat(ReturnRules.positiveQuantity(new BigDecimal("1.25"), "quantity"))
            .isEqualByComparingTo("1.250000");
        assertThatThrownBy(() -> ReturnRules.positiveQuantity(null, "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.positiveQuantity(BigDecimal.ZERO, "quantity")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.positiveQuantity(new BigDecimal("1.0000001"), "quantity"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.positiveQuantity(new BigDecimal("12345678901234"), "quantity"))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesCumulativeOriginalQuantityCap() {
        assertThatCode(() -> ReturnRules.requireQuantityAvailable(new BigDecimal("5"),
            new BigDecimal("2"), new BigDecimal("3"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReturnRules.requireQuantityAvailable(new BigDecimal("5"),
            new BigDecimal("2.000001"), new BigDecimal("3"))).hasMessageContaining("累计退货数量");
        assertThatThrownBy(() -> ReturnRules.requireQuantityAvailable(null, BigDecimal.ZERO, BigDecimal.ONE))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireQuantityAvailable(BigDecimal.ONE,
            new BigDecimal("-1"), BigDecimal.ONE)).isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesRefundMoneyConservationWithoutFloatingPoint() {
        assertThatCode(() -> ReturnRules.requireAllocation(1000, 101, 899)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReturnRules.requireAllocation(1000, 100, 899)).hasMessageContaining("金额不守恒");
        assertThatThrownBy(() -> ReturnRules.requireAllocation(-1, 0, 0)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireAllocation(1, -1, 2)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireAllocation(1, 0, -1)).isInstanceOf(ServiceException.class);
    }

    @Test
    void allowsOnlyDeclaredSagaTransitions() {
        assertThatCode(() -> ReturnRules.requireTransition(Status.PENDING_APPROVAL, Status.PROMOTION_PENDING))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.PROMOTION_PENDING, Status.CASH_REFUND_PENDING))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.PROMOTION_PENDING, Status.PAYMENT_PENDING))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.PROMOTION_PENDING, Status.INVENTORY_PENDING))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.PAYMENT_PENDING, Status.PAYMENT_UNKNOWN))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.PAYMENT_UNKNOWN, Status.PAYMENT_UNKNOWN))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireTransition(Status.INVENTORY_PENDING, Status.COMPLETED))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ReturnRules.requireTransition(Status.COMPLETED, Status.PAYMENT_PENDING))
            .hasMessageContaining("非法状态迁移");
        assertThatThrownBy(() -> ReturnRules.requireTransition(Status.PENDING_APPROVAL, Status.COMPLETED))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsUntrustedIdentifiersAndDigests() {
        assertThatCode(() -> ReturnRules.requireUlid("01K5R000000000000000000001", "returnId"))
            .doesNotThrowAnyException();
        assertThatCode(() -> ReturnRules.requireHash("a".repeat(64), "payload")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ReturnRules.requireUlid("not-ulid", "returnId"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireUlid(null, "returnId")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireHash("A".repeat(64), "payload"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReturnRules.requireHash(null, "payload")).isInstanceOf(ServiceException.class);
    }
}
