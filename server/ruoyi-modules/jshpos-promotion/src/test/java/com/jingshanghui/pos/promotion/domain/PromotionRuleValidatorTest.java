package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证七类白名单算子的唯一参数组合、范围上限与金额精度边界。 */
class PromotionRuleValidatorTest {
    private static final String ID = "01K5R000000000000000000001";
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-08-17T10:00:00+08:00");
    private static final RuleScope SCOPE = new RuleScope(Set.of(1L), Set.of(2L), Set.of(3L),
        Set.of(1101L), Set.of("POS"), Set.of(1));

    @Test
    void acceptsEveryWhitelistedOperatorWithOnlyItsOwnFields() {
        assertValid(RuleType.SPECIAL_PRICE, new RuleBenefit(100L, null, null, null, null, null, List.of()));
        assertValid(RuleType.PERCENT_OFF, new RuleBenefit(null, new BigDecimal("0.10"), null, null,
            null, null, List.of()));
        assertValid(RuleType.AMOUNT_OFF, new RuleBenefit(10L, null, null, null, null, null, List.of()));
        assertValid(RuleType.NTH_ITEM_DISCOUNT, new RuleBenefit(null, new BigDecimal("0.50"), 2, null,
            null, null, List.of()));
        assertValid(RuleType.THRESHOLD_AMOUNT_OFF, new RuleBenefit(10L, null, null, 100L,
            null, null, List.of()));
        assertValid(RuleType.THRESHOLD_QUANTITY_OFF, new RuleBenefit(10L, null, null, null,
            new BigDecimal("2.500000"), null, List.of()));
        assertValid(RuleType.BUNDLE_PRICE, new RuleBenefit(null, null, null, null, null, 150L,
            List.of(new BundleComponent(1L, BigDecimal.ONE), new BundleComponent(2L, new BigDecimal("2")))));
    }

    @Test
    void rejectsUnsupportedScopePriorityResidualAndBoundaryValues() {
        assertInvalid(new RuleVersion(ID, RuleType.AMOUNT_OFF, 100_001, StackMode.STACKABLE, null, AT,
            AT.plusHours(1), SCOPE, amount(1)));
        assertInvalid(new RuleVersion(ID, RuleType.AMOUNT_OFF, -100_001, StackMode.STACKABLE, null, AT,
            AT.plusHours(1), SCOPE, amount(1)));
        RuleScope unsupportedChannel = new RuleScope(Set.of(), Set.of(), Set.of(), Set.of(), Set.of("WEB"), Set.of());
        assertInvalid(rule(RuleType.AMOUNT_OFF, unsupportedChannel, amount(1)));
        RuleScope invalidId = new RuleScope(Set.of(0L), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        assertInvalid(rule(RuleType.AMOUNT_OFF, invalidId, amount(1)));
        assertInvalid(rule(RuleType.AMOUNT_OFF, SCOPE,
            new RuleBenefit(1L, BigDecimal.ONE, null, null, null, null, List.of())));
        assertInvalid(rule(RuleType.AMOUNT_OFF, SCOPE,
            new RuleBenefit(PromotionModels.MAX_SAFE_MONEY_MINOR + 1, null, null, null, null, null, List.of())));
        assertInvalid(rule(RuleType.PERCENT_OFF, SCOPE,
            new RuleBenefit(null, new BigDecimal("1.000000001"), null, null, null, null, List.of())));
        assertInvalid(rule(RuleType.PERCENT_OFF, SCOPE,
            new RuleBenefit(null, null, null, null, null, null, List.of())));
        assertInvalid(rule(RuleType.NTH_ITEM_DISCOUNT, SCOPE,
            new RuleBenefit(null, BigDecimal.ONE, 101, null, null, null, List.of())));
        assertInvalid(rule(RuleType.THRESHOLD_AMOUNT_OFF, SCOPE,
            new RuleBenefit(1L, null, null, 0L, null, null, List.of())));
        assertInvalid(rule(RuleType.THRESHOLD_QUANTITY_OFF, SCOPE,
            new RuleBenefit(1L, null, null, null, new BigDecimal("1.0000001"), null, List.of())));
        assertInvalid(rule(RuleType.BUNDLE_PRICE, SCOPE,
            new RuleBenefit(null, null, null, null, null, 1L,
                List.of(new BundleComponent(1L, BigDecimal.ONE), new BundleComponent(1L, BigDecimal.ONE)))));
        assertInvalid(rule(RuleType.BUNDLE_PRICE, SCOPE,
            new RuleBenefit(null, null, null, null, null, 1L,
                List.of(new BundleComponent(1L, new BigDecimal("1.0000001"))))));
    }

    @Test
    void rejectsMoneyAndAllocatorOverflowBeforeArithmeticCanWrap() {
        assertThatThrownBy(() -> new BasketLine(ID, 1, 1L, null, null, BigDecimal.ONE,
            PromotionModels.MAX_SAFE_MONEY_MINOR + 1)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> new PromotionEngine().quote(new QuoteRequest(AT, 1101L, "POS", List.of(
            new BasketLine(ID, 1, 1L, null, null, BigDecimal.ONE, PromotionModels.MAX_SAFE_MONEY_MINOR),
            new BasketLine("01K5R000000000000000000002", 2, 2L, null, null, BigDecimal.ONE, 1)), List.of())))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PRM-ENGINE-005");
        assertThatThrownBy(() -> LargestRemainderAllocator.allocate(1, List.of(
            new LargestRemainderAllocator.Weight(ID, 1, 1L, PromotionModels.MAX_SAFE_MONEY_MINOR),
            new LargestRemainderAllocator.Weight("01K5R000000000000000000002", 2, 2L, 1))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PRM-ALLOC-005");
    }

    private static RuleBenefit amount(long value) {
        return new RuleBenefit(value, null, null, null, null, null, List.of());
    }

    private static void assertValid(RuleType type, RuleBenefit benefit) {
        assertThatCode(() -> PromotionRuleValidator.validate(rule(type, SCOPE, benefit))).doesNotThrowAnyException();
    }

    private static void assertInvalid(RuleVersion rule) {
        assertThatThrownBy(() -> PromotionRuleValidator.validate(rule)).isInstanceOf(ServiceException.class);
    }

    private static RuleVersion rule(RuleType type, RuleScope scope, RuleBenefit benefit) {
        return new RuleVersion(ID, type, 1, StackMode.STACKABLE, null, AT, AT.plusHours(1), scope, benefit);
    }
}
