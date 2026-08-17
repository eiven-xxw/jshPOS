package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 促销计算的不可变领域值；金额统一为最小货币单位整数，数量和比例使用 BigDecimal。
 */
public final class PromotionModels {
    /** 与 JSON、Dart VM 和数据库往返均精确的最大金额整数。 */
    public static final long MAX_SAFE_MONEY_MINOR = 9_007_199_254_740_991L;
    private PromotionModels() {
    }

    /** Gate 5A 基础规则白名单。 */
    public enum RuleType {
        SPECIAL_PRICE, PERCENT_OFF, AMOUNT_OFF, NTH_ITEM_DISCOUNT,
        BUNDLE_PRICE, THRESHOLD_AMOUNT_OFF, THRESHOLD_QUANTITY_OFF
    }

    /** 叠加模式；不存在任意脚本执行入口。 */
    public enum StackMode { EXCLUSIVE, STACKABLE, BEST_OF_GROUP }

    /**
     * 购物行输入。
     *
     * @param lineId 行 ULID
     * @param lineNo 稳定行号
     * @param skuId 商品 SKU
     * @param categoryId 分类
     * @param brandId 品牌
     * @param quantity 基础单位数量
     * @param unitPriceMinor 当前基础/门店单价
     */
    public record BasketLine(String lineId, int lineNo, Long skuId, Long categoryId, Long brandId,
                             BigDecimal quantity, long unitPriceMinor) {
        public BasketLine {
            if (lineId == null || !lineId.matches("^[0-9A-HJKMNP-TV-Z]{26}$") || lineNo <= 0
                || skuId == null || skuId <= 0 || quantity == null || quantity.signum() <= 0
                || quantity.scale() > 6 || quantity.precision() > 20
                || unitPriceMinor < 0 || unitPriceMinor > MAX_SAFE_MONEY_MINOR) {
                throw new ServiceException("PRM-ENGINE-001: 购物行无效", 400);
            }
        }
    }

    /**
     * 规则适用范围；各维度均为空表示全商品，非空维度之间采用 AND。
     *
     * @param skuIds SKU 白名单
     * @param categoryIds 分类白名单
     * @param brandIds 品牌白名单
     * @param storeIds 门店白名单
     * @param channels 渠道白名单
     * @param businessDays ISO-8601 业务星期白名单，1为周一、7为周日
     */
    public record RuleScope(Set<Long> skuIds, Set<Long> categoryIds, Set<Long> brandIds,
                            Set<Long> storeIds, Set<String> channels, Set<Integer> businessDays) {
        /** 兼容不限制业务星期的构造入口。 */
        public RuleScope(Set<Long> skuIds, Set<Long> categoryIds, Set<Long> brandIds,
                         Set<Long> storeIds, Set<String> channels) {
            this(skuIds, categoryIds, brandIds, storeIds, channels, Set.of());
        }

        public RuleScope {
            skuIds = skuIds == null ? Set.of() : Set.copyOf(skuIds);
            categoryIds = categoryIds == null ? Set.of() : Set.copyOf(categoryIds);
            brandIds = brandIds == null ? Set.of() : Set.copyOf(brandIds);
            storeIds = storeIds == null ? Set.of() : Set.copyOf(storeIds);
            channels = channels == null ? Set.of() : Set.copyOf(channels);
            businessDays = businessDays == null ? Set.of() : Set.copyOf(businessDays);
            if (businessDays.stream().anyMatch(day -> day == null || day < 1 || day > 7)) {
                throw new ServiceException("PRM-RULE-021: 业务星期必须位于1到7", 400);
            }
        }

        /** 判断订单和行是否同时满足显式范围。 */
        public boolean matches(BasketLine line, Long storeId, String channel, OffsetDateTime businessTime) {
            return (skuIds.isEmpty() || skuIds.contains(line.skuId()))
                && (categoryIds.isEmpty() || categoryIds.contains(line.categoryId()))
                && (brandIds.isEmpty() || brandIds.contains(line.brandId()))
                && (storeIds.isEmpty() || storeIds.contains(storeId))
                && (channels.isEmpty() || channels.contains(channel))
                && (businessDays.isEmpty() || (businessTime != null
                    && businessDays.contains(businessTime.getDayOfWeek().getValue())));
        }

        /** 供不限制业务星期的静态作用域测试使用。 */
        public boolean matches(BasketLine line, Long storeId, String channel) {
            return matches(line, storeId, channel, OffsetDateTime.parse("2026-08-17T00:00:00Z"));
        }
    }

    /**
     * 白名单化优惠参数。
     *
     * @param amountMinor 固定金额、特价或减免额
     * @param discountRate 折扣率，范围 0..1
     * @param nth 第 N 件
     * @param thresholdMinor 金额门槛
     * @param thresholdQuantity 数量门槛
     * @param bundlePriceMinor 组合价
     * @param bundleComponents 组合组件
     */
    public record RuleBenefit(Long amountMinor, BigDecimal discountRate, Integer nth, Long thresholdMinor,
                              BigDecimal thresholdQuantity, Long bundlePriceMinor,
                              List<BundleComponent> bundleComponents) {
        public RuleBenefit {
            bundleComponents = bundleComponents == null ? List.of() : List.copyOf(bundleComponents);
        }
    }

    /**
     * 组合促销组件。
     *
     * @param skuId SKU
     * @param quantity 每套所需数量
     */
    public record BundleComponent(Long skuId, BigDecimal quantity) {
    }

    /**
     * 已发布规则版本。
     *
     * @param ruleVersionId 规则版本 ULID
     * @param ruleType 规则类型
     * @param priority 优先级，数值越大越先计算
     * @param stackMode 叠加模式
     * @param exclusiveGroup 互斥组
     * @param effectiveFrom 生效开始，含
     * @param effectiveTo 生效结束，不含
     * @param scope 适用范围
     * @param benefit 优惠参数
     */
    public record RuleVersion(String ruleVersionId, RuleType ruleType, int priority, StackMode stackMode,
                              String exclusiveGroup, OffsetDateTime effectiveFrom, OffsetDateTime effectiveTo,
                              RuleScope scope, RuleBenefit benefit) {
        public RuleVersion {
            if (ruleVersionId == null || !ruleVersionId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")
                || ruleType == null || stackMode == null || effectiveFrom == null || scope == null
                || benefit == null || (effectiveTo != null && !effectiveTo.isAfter(effectiveFrom))) {
                throw new ServiceException("PRM-RULE-001: 规则版本无效", 400);
            }
            if (stackMode == StackMode.BEST_OF_GROUP && (exclusiveGroup == null || exclusiveGroup.isBlank())) {
                throw new ServiceException("PRM-RULE-002: 优选规则必须指定互斥组", 400);
            }
        }

        /** 判断业务时间是否位于左闭右开生效窗口。 */
        public boolean activeAt(OffsetDateTime businessTime) {
            return businessTime != null && !businessTime.isBefore(effectiveFrom)
                && (effectiveTo == null || businessTime.isBefore(effectiveTo));
        }
    }

    /**
     * 促销询价输入。
     *
     * @param businessTime 带时区业务时间
     * @param storeId 门店
     * @param channel 渠道
     * @param lines 购物行
     * @param rules 已发布规则版本
     */
    public record QuoteRequest(OffsetDateTime businessTime, Long storeId, String channel,
                               List<BasketLine> lines, List<RuleVersion> rules) {
        public QuoteRequest {
            lines = lines == null ? List.of() : List.copyOf(lines);
            rules = rules == null ? List.of() : List.copyOf(rules);
            if (businessTime == null || storeId == null || storeId <= 0 || channel == null || channel.isBlank()
                || lines.isEmpty() || lines.size() > 500) {
                throw new ServiceException("PRM-ENGINE-002: 询价上下文无效", 400);
            }
        }
    }

    /**
     * 单行促销结果。
     *
     * @param lineId 行标识
     * @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额
     * @param payableAmountMinor 应付金额
     */
    public record QuoteLine(String lineId, long grossAmountMinor, long discountAmountMinor,
                            long payableAmountMinor) {
    }

    /**
     * 规则解释记录。
     *
     * @param sourceId 规则版本
     * @param code 解释码
     */
    public record Explanation(String sourceId, String code) {
    }

    /**
     * 实际采用规则及其行级金额贡献。
     *
     * @param sourceId 规则版本
     * @param amountMinor 本规则优惠总额
     * @param lineAllocations 各购物行贡献金额
     */
    public record AppliedAdjustment(String sourceId, long amountMinor, Map<String, Long> lineAllocations) {
        public AppliedAdjustment { lineAllocations = Map.copyOf(lineAllocations); }
    }

    /**
     * 确定性促销结果。
     *
     * @param grossAmountMinor 原总额
     * @param discountAmountMinor 优惠总额
     * @param payableAmountMinor 应付总额
     * @param lines 行结果
     * @param appliedRuleIds 实际采用规则
     * @param explanations 解释记录
     * @param adjustments 实际采用规则的行级金额贡献
     */
    public record QuoteResult(long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                              List<QuoteLine> lines, List<String> appliedRuleIds,
                              List<Explanation> explanations, List<AppliedAdjustment> adjustments) {
        public QuoteResult {
            lines = List.copyOf(lines);
            appliedRuleIds = List.copyOf(appliedRuleIds);
            explanations = List.copyOf(explanations);
            adjustments = List.copyOf(adjustments);
        }

        /** 返回稳定的行优惠映射。 */
        public Map<String, Long> lineDiscounts() {
            return lines.stream().collect(java.util.stream.Collectors.toMap(
                QuoteLine::lineId, QuoteLine::discountAmountMinor, (left, right) -> left,
                java.util.LinkedHashMap::new));
        }
    }
}
