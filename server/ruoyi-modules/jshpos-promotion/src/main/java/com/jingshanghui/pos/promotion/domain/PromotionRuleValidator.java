package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.PromotionModels.BundleComponent;
import com.jingshanghui.pos.promotion.domain.PromotionModels.RuleBenefit;
import com.jingshanghui.pos.promotion.domain.PromotionModels.RuleVersion;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import static com.jingshanghui.pos.promotion.domain.PromotionModels.MAX_SAFE_MONEY_MINOR;

/**
 * 基础促销白名单静态校验器；发布前拒绝缺失参数、残留参数和未准入能力。
 */
public final class PromotionRuleValidator {
    private static final Set<String> CHANNELS = Set.of("POS", "MOBILE_POS", "SELF_CHECKOUT");

    private PromotionRuleValidator() {
    }

    /** 校验规则作用域、参数精度和每种算子的唯一合法参数组合。 */
    public static void validate(RuleVersion rule) {
        if (rule.priority() < -100_000 || rule.priority() > 100_000) {
            fail("优先级越界");
        }
        var scope = rule.scope();
        if (scope.skuIds().size() > 256 || scope.categoryIds().size() > 256
            || scope.brandIds().size() > 256 || scope.storeIds().size() > 256
            || scope.channels().size() > 16 || scope.businessDays().size() > 7
            || scope.skuIds().stream().anyMatch(PromotionRuleValidator::invalidId)
            || scope.categoryIds().stream().anyMatch(PromotionRuleValidator::invalidId)
            || scope.brandIds().stream().anyMatch(PromotionRuleValidator::invalidId)
            || scope.storeIds().stream().anyMatch(PromotionRuleValidator::invalidId)
            || !CHANNELS.containsAll(scope.channels())) {
            fail("作用域越界或包含未支持维度值");
        }
        RuleBenefit benefit = rule.benefit();
        switch (rule.ruleType()) {
            case SPECIAL_PRICE -> {
                nonNegative(benefit.amountMinor(), "特价");
                requireUnused(benefit, false, true, true, true, true, true);
            }
            case PERCENT_OFF -> {
                rate(benefit.discountRate());
                requireUnused(benefit, true, false, true, true, true, true);
            }
            case AMOUNT_OFF -> {
                nonNegative(benefit.amountMinor(), "直减金额");
                requireUnused(benefit, false, true, true, true, true, true);
            }
            case NTH_ITEM_DISCOUNT -> {
                if (benefit.nth() == null || benefit.nth() < 2 || benefit.nth() > 100) fail("第N件序号无效");
                rate(benefit.discountRate());
                requireUnused(benefit, true, false, false, true, true, true);
            }
            case THRESHOLD_AMOUNT_OFF -> {
                positive(benefit.thresholdMinor(), "满额门槛");
                nonNegative(benefit.amountMinor(), "满额减金额");
                requireUnused(benefit, false, true, true, false, true, true);
            }
            case THRESHOLD_QUANTITY_OFF -> {
                decimalPositive(benefit.thresholdQuantity(), 6, "满件门槛");
                nonNegative(benefit.amountMinor(), "满件减金额");
                requireUnused(benefit, false, true, true, true, false, true);
            }
            case BUNDLE_PRICE -> {
                nonNegative(benefit.bundlePriceMinor(), "组合价");
                if (benefit.bundleComponents().isEmpty() || benefit.bundleComponents().size() > 32) {
                    fail("组合组件数量无效");
                }
                Set<Long> skuIds = new HashSet<>();
                for (BundleComponent component : benefit.bundleComponents()) {
                    if (invalidId(component.skuId()) || !skuIds.add(component.skuId())) fail("组合SKU无效或重复");
                    decimalPositive(component.quantity(), 6, "组合数量");
                }
                requireUnused(benefit, true, true, true, true, true, false);
            }
        }
    }

    private static void requireUnused(RuleBenefit value, boolean amount, boolean rate, boolean nth,
                                      boolean thresholdAmount, boolean thresholdQuantity, boolean bundle) {
        if ((amount && value.amountMinor() != null) || (rate && value.discountRate() != null)
            || (nth && value.nth() != null) || (thresholdAmount && value.thresholdMinor() != null)
            || (thresholdQuantity && value.thresholdQuantity() != null)
            || (bundle && (value.bundlePriceMinor() != null || !value.bundleComponents().isEmpty()))) {
            fail("规则包含其他算子的残留参数");
        }
    }

    private static boolean invalidId(Long value) {
        return value == null || value <= 0;
    }

    private static void nonNegative(Long value, String name) {
        if (value == null || value < 0 || value > MAX_SAFE_MONEY_MINOR) fail(name + "无效");
    }

    private static void positive(Long value, String name) {
        if (value == null || value <= 0 || value > MAX_SAFE_MONEY_MINOR) fail(name + "必须大于零");
    }

    private static void rate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0 || value.scale() > 8) {
            fail("折扣率必须位于0到1且最多八位小数");
        }
    }

    private static void decimalPositive(BigDecimal value, int scale, String name) {
        if (value == null || value.signum() <= 0 || value.scale() > scale || value.precision() > 20) {
            fail(name + "精度或范围无效");
        }
    }

    private static void fail(String detail) {
        throw new ServiceException("PRM-CAPABILITY-UNSUPPORTED: " + detail, 400);
    }
}
