package com.jingshanghui.pos.procurement.domain;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖补货最低/最高、在途、单位换算、最小量和倍数的确定性不变量。 */
class ReplenishmentRulesTest {

    @Test
    void validatesIdentifiersTextAndQuantities() {
        assertThat(ReplenishmentRules.ulid("01K2A000000000000000000001", "id")).hasSize(26);
        assertThat(ReplenishmentRules.text(" reason ", 16, "RPL-T")).isEqualTo("reason");
        assertThat(ReplenishmentRules.nonNegative(new BigDecimal("0.1"), "qty"))
            .isEqualByComparingTo("0.100000");
        assertThat(ReplenishmentRules.positive(BigDecimal.ONE, "qty"))
            .isEqualByComparingTo("1.000000");
        assertThatThrownBy(() -> ReplenishmentRules.ulid(null, "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.ulid("bad", "id")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.text(null, 8, "RPL-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.text("", 8, "RPL-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.text("123456789", 8, "RPL-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.text("bad\ntext", 16, "RPL-T")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.nonNegative(null, "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.nonNegative(new BigDecimal("-1"), "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.nonNegative(new BigDecimal("1.0000001"), "qty")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.nonNegative(new BigDecimal("12345678901234567890"), "qty"))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.positive(BigDecimal.ZERO, "qty")).isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsInvalidRuleShapes() {
        ReplenishmentRules.requireRule(new BigDecimal("5"), new BigDecimal("10"),
            BigDecimal.ONE, BigDecimal.ONE, 1, 1);
        assertThatThrownBy(() -> ReplenishmentRules.requireRule(new BigDecimal("11"), new BigDecimal("10"),
            BigDecimal.ONE, BigDecimal.ONE, 1, 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.requireRule(BigDecimal.ZERO, BigDecimal.TEN,
            BigDecimal.ZERO, BigDecimal.ONE, 1, 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.requireRule(BigDecimal.ZERO, BigDecimal.TEN,
            BigDecimal.ONE, BigDecimal.ZERO, 1, 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.requireRule(BigDecimal.ZERO, BigDecimal.TEN,
            BigDecimal.ONE, BigDecimal.ONE, 0, 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.requireRule(BigDecimal.ZERO, BigDecimal.TEN,
            BigDecimal.ONE, BigDecimal.ONE, 1, 0)).isInstanceOf(ServiceException.class);
    }

    @Test
    void returnsNoSuggestionAtOrAboveMinimum() {
        assertThat(ReplenishmentRules.calculate(new BigDecimal("5"), BigDecimal.ZERO,
            new BigDecimal("5"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 1, 1, false)).isEmpty();
        assertThat(ReplenishmentRules.calculate(new BigDecimal("4"), BigDecimal.ONE,
            new BigDecimal("5"), BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 1, 1, true)).isEmpty();
    }

    @Test
    void replenishesToMaximumAndRoundsToPurchaseMultiple() {
        var value = ReplenishmentRules.calculate(new BigDecimal("3"), BigDecimal.ZERO,
            new BigDecimal("5"), new BigDecimal("10"), new BigDecimal("2"), new BigDecimal("3"),
            2, 1, false).orElseThrow();
        assertThat(value.effectiveQuantity()).isEqualByComparingTo("3.000000");
        assertThat(value.requiredBaseQuantity()).isEqualByComparingTo("7.000000");
        assertThat(value.suggestedPurchaseQuantity()).isEqualByComparingTo("6.000000");
    }

    @Test
    void appliesMinimumOrderAndConfirmedTransitDeterministically() {
        var minimum = ReplenishmentRules.calculate(new BigDecimal("4"), BigDecimal.ZERO,
            new BigDecimal("5"), new BigDecimal("6"), new BigDecimal("10"), new BigDecimal("4"),
            1, 1, false).orElseThrow();
        assertThat(minimum.suggestedPurchaseQuantity()).isEqualByComparingTo("12.000000");
        var transit = ReplenishmentRules.calculate(new BigDecimal("1"), new BigDecimal("2"),
            new BigDecimal("5"), new BigDecimal("10"), BigDecimal.ONE, BigDecimal.ONE,
            1, 1, true).orElseThrow();
        assertThat(transit.effectiveQuantity()).isEqualByComparingTo("3.000000");
        assertThat(transit.suggestedPurchaseQuantity()).isEqualByComparingTo("7.000000");
    }

    @Test
    void supportsNegativeAvailableAndRejectsInvalidInputs() {
        var value = ReplenishmentRules.calculate(new BigDecimal("-2"), BigDecimal.ZERO,
            BigDecimal.ZERO, new BigDecimal("8"), BigDecimal.ONE, BigDecimal.ONE,
            1, 1, false).orElseThrow();
        assertThat(value.requiredBaseQuantity()).isEqualByComparingTo("10.000000");
        assertThatThrownBy(() -> ReplenishmentRules.calculate(null, BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1, 1, false))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.calculate(new BigDecimal("1.0000001"), BigDecimal.ZERO,
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1, 1, false))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> ReplenishmentRules.calculate(BigDecimal.ZERO, new BigDecimal("-1"),
            BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 1, 1, true))
            .isInstanceOf(ServiceException.class);
    }
}
