package com.jingshanghui.pos.reporting.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.*;

/** RPT-002 差异固定优先级、UNKNOWN 与人工处理状态机回归。 */
class PaymentReconciliationRulesTest {
    private static final LocalDate DAY = LocalDate.of(2026, 8, 17);

    @Test void classifiesMissingMatchedAndEveryDifferenceInStablePriority() {
        assertThat(classify(false,true,"CNY","CNY",null,100L,null,"SUCCEEDED",null,DAY))
            .isEqualTo("MISSING_INTERNAL");
        assertThat(classify(true,false,"CNY",null,100L,null,"SUCCEEDED",null,DAY,null))
            .isEqualTo("MISSING_BILL");
        assertThat(classify(true,true,"CNY","USD",100L,101L,"SUCCEEDED","FAILED",DAY,DAY.plusDays(1)))
            .isEqualTo("CURRENCY_MISMATCH");
        assertThat(classify(true,true,"CNY","CNY",100L,101L,"SUCCEEDED","FAILED",DAY,DAY.plusDays(1)))
            .isEqualTo("AMOUNT_MISMATCH");
        assertThat(classify(true,true,"CNY","CNY",100L,100L,"UNKNOWN","SUCCEEDED",DAY,DAY.plusDays(1)))
            .isEqualTo("STATUS_MISMATCH");
        assertThat(classify(true,true,"CNY","CNY",100L,100L,"SUCCEEDED","SUCCEEDED",DAY,DAY.plusDays(1)))
            .isEqualTo("BUSINESS_DATE_MISMATCH");
        assertThat(classify(true,true,"CNY","CNY",100L,100L,"UNKNOWN","UNKNOWN",DAY,DAY))
            .isEqualTo("MATCHED");
    }

    @Test void validatesTypesStatusesAmountsAndStateTransitions() {
        assertThat(PaymentReconciliationRules.requireFactType("PAYMENT")).isEqualTo("PAYMENT");
        assertThat(PaymentReconciliationRules.requireLifecycleStatus("UNKNOWN")).isEqualTo("UNKNOWN");
        assertThat(PaymentReconciliationRules.requireAmount(0)).isZero();
        assertThat(PaymentReconciliationRules.requireDifference("AMOUNT_MISMATCH")).isEqualTo("AMOUNT_MISMATCH");
        assertThat(PaymentReconciliationRules.requireHandling("ASSIGNED")).isEqualTo("ASSIGNED");
        assertThat(PaymentReconciliationRules.transition("OPEN","ASSIGNED")).isEqualTo("ASSIGNED");
        assertThat(PaymentReconciliationRules.transition("OPEN","RESOLVED")).isEqualTo("RESOLVED");
        assertThat(PaymentReconciliationRules.transition("ASSIGNED","IGNORED")).isEqualTo("IGNORED");
        assertThatThrownBy(() -> PaymentReconciliationRules.requireFactType("CHARGE"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.requireLifecycleStatus("SUCCESS"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.requireAmount(-1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.requireDifference("OTHER"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.requireHandling("PENDING"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.transition("MATCHED","RESOLVED"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> PaymentReconciliationRules.transition("RESOLVED","IGNORED"))
            .isInstanceOf(ServiceException.class);
    }

    private String classify(boolean hasInternal, boolean hasBill, String internalCurrency, String billCurrency,
                            Long internalAmount, Long billAmount, String internalStatus, String billStatus,
                            LocalDate internalDate, LocalDate billDate) {
        return PaymentReconciliationRules.classify(hasInternal,hasBill,internalCurrency,billCurrency,
            internalAmount,billAmount,internalStatus,billStatus,internalDate,billDate);
    }
}
