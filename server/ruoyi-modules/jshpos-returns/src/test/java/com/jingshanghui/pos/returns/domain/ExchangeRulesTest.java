package com.jingshanghui.pos.returns.domain;

import com.jingshanghui.pos.returns.domain.ExchangeStates.Status;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** EXG-001 两条腿、金额、摘要和可恢复状态机不变量。 */
class ExchangeRulesTest {

    @Test
    void allowsEveryDeclaredRecoveryPathAndRejectsShortcuts() {
        allow(Status.DRAFT, Status.APPROVED);
        allow(Status.DRAFT, Status.FAILED);
        allow(Status.APPROVED, Status.RETURN_PENDING);
        allow(Status.APPROVED, Status.FAILED);
        allow(Status.RETURN_PENDING, Status.RETURN_UNKNOWN);
        allow(Status.RETURN_PENDING, Status.RETURN_COMPLETED);
        allow(Status.RETURN_PENDING, Status.FAILED);
        allow(Status.RETURN_PENDING, Status.MANUAL_RECOVERY_REQUIRED);
        allow(Status.RETURN_UNKNOWN, Status.RETURN_UNKNOWN);
        allow(Status.RETURN_UNKNOWN, Status.RETURN_COMPLETED);
        allow(Status.RETURN_UNKNOWN, Status.MANUAL_RECOVERY_REQUIRED);
        allow(Status.RETURN_COMPLETED, Status.SALE_PENDING);
        allow(Status.RETURN_COMPLETED, Status.MANUAL_RECOVERY_REQUIRED);
        allow(Status.SALE_PENDING, Status.SALE_UNKNOWN);
        allow(Status.SALE_PENDING, Status.COMPLETED);
        allow(Status.SALE_PENDING, Status.MANUAL_RECOVERY_REQUIRED);
        allow(Status.SALE_UNKNOWN, Status.SALE_UNKNOWN);
        allow(Status.SALE_UNKNOWN, Status.COMPLETED);
        allow(Status.SALE_UNKNOWN, Status.MANUAL_RECOVERY_REQUIRED);
        allow(Status.FAILED, Status.CLOSED);
        allow(Status.MANUAL_RECOVERY_REQUIRED, Status.RETURN_PENDING);
        allow(Status.MANUAL_RECOVERY_REQUIRED, Status.SALE_PENDING);
        allow(Status.MANUAL_RECOVERY_REQUIRED, Status.CLOSED);
        allow(Status.COMPLETED, Status.CLOSED);
        assertThatThrownBy(() -> ExchangeRules.requireTransition(Status.DRAFT, Status.COMPLETED))
            .hasMessageContaining("非法换货状态迁移");
        assertThatThrownBy(() -> ExchangeRules.requireTransition(Status.CLOSED, Status.DRAFT))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesSeparatePositiveOwnerAmountsAndObservedEquality() {
        assertThatCode(() -> ExchangeRules.requireExpectedAmounts(900, 1200)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExchangeRules.requireExpectedAmounts(0, 1200)).hasMessageContaining("必须为正数");
        assertThatThrownBy(() -> ExchangeRules.requireExpectedAmounts(900, -1)).isInstanceOf(ServiceException.class);
        assertThatCode(() -> ExchangeRules.requireObservedAmount(900, 900, "Return"))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> ExchangeRules.requireObservedAmount(900, 901, "Return"))
            .hasMessageContaining("权威金额");
    }

    @Test
    void enforcesDistinctCanonicalOwnerIdentities() {
        String original = "01K7A000000000000000000001";
        String replacement = "01K7A000000000000000000002";
        assertThatCode(() -> ExchangeRules.requireDistinctOrders(original, replacement)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExchangeRules.requireDistinctOrders(original, original))
            .hasMessageContaining("不得复用");
        assertThatThrownBy(() -> ExchangeRules.requireDistinctOrders("bad", replacement))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ExchangeRules.requireUlid(null, "id")).isInstanceOf(ServiceException.class);
    }

    @Test
    void acceptsOnlyLowercaseSha256() {
        assertThatCode(() -> ExchangeRules.requireHash("a".repeat(64), "digest")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ExchangeRules.requireHash("A".repeat(64), "digest"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ExchangeRules.requireHash(null, "digest"))
            .isInstanceOf(ServiceException.class);
    }

    private void allow(Status before, Status after) {
        assertThatCode(() -> ExchangeRules.requireTransition(before, after)).doesNotThrowAnyException();
    }
}
