package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖动态盘点阈值、差异、状态机和职责分离不变量。 */
class StocktakeRulesTest {

    @Test
    void normalizesCountThresholdAndVariance() {
        assertThat(StocktakeRules.countQuantity(new BigDecimal("0"))).isEqualByComparingTo("0.000000");
        assertThat(StocktakeRules.threshold(new BigDecimal("2.5"))).isEqualByComparingTo("2.500000");
        assertThat(StocktakeRules.variance(new BigDecimal("9"), new BigDecimal("10.000000")))
            .isEqualByComparingTo("-1.000000");
    }

    @Test
    void rejectsIllegalCountAndThreshold() {
        assertThatThrownBy(() -> StocktakeRules.countQuantity(null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.countQuantity(new BigDecimal("-1"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.countQuantity(new BigDecimal("1.0000001"))).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.countQuantity(new BigDecimal("10000000000000000000")))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.threshold(new BigDecimal("10000000000000")))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void requiresSecondCountOnlyAboveThreshold() {
        assertThat(StocktakeRules.requiresRecount(new BigDecimal("1.000001"), BigDecimal.ONE, 1)).isTrue();
        assertThat(StocktakeRules.requiresRecount(BigDecimal.ONE, BigDecimal.ONE, 1)).isFalse();
        assertThat(StocktakeRules.requiresRecount(new BigDecimal("2"), BigDecimal.ONE, 2)).isFalse();
    }

    @Test
    void enforcesStateTransitions() {
        StocktakeRules.requireCountable("COUNTING");
        StocktakeRules.requireCountable("RECOUNT_REQUIRED");
        StocktakeRules.requireSubmittable("COUNTING");
        StocktakeRules.requireSubmittable("RECOUNT_REQUIRED");
        StocktakeRules.requireReviewable("PENDING_REVIEW");
        StocktakeRules.requireApprovable("REVIEWED");
        assertThatThrownBy(() -> StocktakeRules.requireCountable("POSTED")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSubmittable("POSTED")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireReviewable("COUNTING")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireApprovable("PENDING_REVIEW")).isInstanceOf(ServiceException.class);
    }

    @Test
    void enforcesThreeDifferentActors() {
        StocktakeRules.requireSegregatedActors(1L, 2L, 3L);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(null, 2L, 3L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(1L, null, 3L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(1L, 2L, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(1L, 1L, 3L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(1L, 2L, 1L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> StocktakeRules.requireSegregatedActors(1L, 2L, 2L)).isInstanceOf(ServiceException.class);
    }
}
