package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.LargestRemainderAllocator.Weight;
import com.jingshanghui.pos.promotion.domain.PromotionModels.AppliedAdjustment;
import com.jingshanghui.pos.promotion.domain.PromotionModels.Explanation;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jingshanghui.pos.promotion.domain.PromotionModels.MAX_SAFE_MONEY_MINOR;

/**
 * PRM-002 人工优惠纯领域引擎；固定执行在规则优惠之后、成交分摊之前。
 */
public final class ManualAdjustmentEngine {
    /** 获准的人工优惠动作。 */
    public enum ActionType { LINE_FIXED_PRICE, ORDER_AMOUNT_OFF, ORDER_PERCENT_OFF, ROUNDING }

    /** 支付方式只用于限制现金抹零，不能作为支付事实。 */
    public enum PaymentMethod { CASH, NON_CASH }

    /**
     * 从 Gate 0 已发布配置中冻结的人工优惠策略。
     *
     * @param policyVersionId 配置版本
     * @param policySha256 配置摘要
     * @param withoutApprovalMinor 无需复核的单次增量优惠上限
     * @param withApprovalMinor 复核后的单次增量优惠上限
     * @param minimumLinePayableMinor 手工改价后的行最低应收
     * @param maximumRoundingMinor 单次现金抹零绝对上限
     * @param roundingMultiplesMinor 允许向下取整的分值集合
     */
    public record Policy(long policyVersionId, String policySha256, long withoutApprovalMinor,
                         long withApprovalMinor, long minimumLinePayableMinor,
                         long maximumRoundingMinor, List<Long> roundingMultiplesMinor) {
        public Policy {
            List<Long> suppliedMultiples = roundingMultiplesMinor == null ? List.of() : roundingMultiplesMinor;
            if (policyVersionId <= 0 || policySha256 == null || !policySha256.matches("^[a-f0-9]{64}$")
                || withoutApprovalMinor < 0 || withApprovalMinor < withoutApprovalMinor
                || withApprovalMinor > MAX_SAFE_MONEY_MINOR || minimumLinePayableMinor < 0
                || maximumRoundingMinor < 0 || maximumRoundingMinor > withApprovalMinor
                || suppliedMultiples.isEmpty() || suppliedMultiples.stream().anyMatch(
                    value -> value == null || value <= 0 || value > 10_000)) {
                throw new ServiceException("PRM-AUTH-010: 人工优惠策略无效", 500);
            }
            roundingMultiplesMinor = List.copyOf(suppliedMultiples);
        }
    }

    /**
     * 行稳定元数据。
     *
     * @param lineId 原购物行 ULID
     * @param lineNo 稳定行号
     * @param skuId SKU
     * @param quantity 基础单位数量
     */
    public record LineContext(String lineId, int lineNo, Long skuId, BigDecimal quantity) {
        public LineContext {
            if (lineId == null || !lineId.matches("^[0-9A-HJKMNP-TV-Z]{26}$") || lineNo <= 0
                || skuId == null || skuId <= 0 || quantity == null || quantity.signum() <= 0
                || quantity.scale() > 6 || quantity.precision() > 20) {
                throw new ServiceException("PRM-AMOUNT-010: 人工优惠行上下文无效", 400);
            }
        }
    }

    /**
     * 人工优惠输入。
     *
     * @param authorizationId 授权 ULID，同时作为解释来源
     * @param actionType 动作
     * @param lineId 行改价目标；整单动作为空
     * @param amountOrRate 分金额、折扣率或抹零倍数的十进制字符串
     * @param paymentMethod 支付方式
     */
    public record Command(String authorizationId, ActionType actionType, String lineId,
                          String amountOrRate, PaymentMethod paymentMethod) {
        public Command {
            if (authorizationId == null || !authorizationId.matches("^[0-9A-HJKMNP-TV-Z]{26}$")
                || actionType == null || amountOrRate == null || amountOrRate.isBlank()
                || amountOrRate.length() > 32 || paymentMethod == null) {
                throw new ServiceException("PRM-AUTH-011: 人工优惠命令无效", 400);
            }
        }
    }

    /**
     * 预检结果。
     *
     * @param result 应用动作后的确定性报价
     * @param incrementalDiscountMinor 本动作新增优惠
     * @param requiresApproval 是否必须由另一主体复核
     */
    public record Preview(QuoteResult result, long incrementalDiscountMinor, boolean requiresApproval) { }

    /** 按固定动作语义计算人工优惠；超过硬上限或出现增价时失败关闭。 */
    public Preview preview(QuoteResult current, List<LineContext> contexts, Command command, Policy policy) {
        requireCurrent(current, contexts);
        Map<String, Long> allocations = switch (command.actionType()) {
            case LINE_FIXED_PRICE -> lineFixedPrice(current, contexts, command.lineId(), command.amountOrRate(), policy);
            case ORDER_AMOUNT_OFF -> orderAmount(current, contexts, parseMinor(command.amountOrRate()));
            case ORDER_PERCENT_OFF -> orderPercent(current, contexts, parseRate(command.amountOrRate()));
            case ROUNDING -> rounding(current, contexts, command, policy);
        };
        long incremental = sum(allocations.values());
        if (incremental <= 0 || incremental > policy.withApprovalMinor()) {
            throw new ServiceException("PRM-AUTH-012: 人工优惠超过策略上限或没有产生优惠", 422);
        }
        QuoteResult result = apply(current, command, allocations, incremental);
        return new Preview(result, incremental, incremental > policy.withoutApprovalMinor());
    }

    private Map<String, Long> lineFixedPrice(QuoteResult current, List<LineContext> contexts, String lineId,
                                             String amountOrRate, Policy policy) {
        if (lineId == null) throw new ServiceException("PRM-AMOUNT-011: 行改价必须指定行", 400);
        long fixedUnitPrice = parseMinor(amountOrRate);
        LineContext context = contexts.stream().filter(value -> value.lineId().equals(lineId)).findFirst()
            .orElseThrow(() -> new ServiceException("PRM-AMOUNT-012: 改价行不存在", 404));
        QuoteLine line = current.lines().stream().filter(value -> value.lineId().equals(lineId)).findFirst()
            .orElseThrow(() -> new ServiceException("PRM-AMOUNT-012: 改价行不存在", 404));
        long target = exactMinor(context.quantity().multiply(BigDecimal.valueOf(fixedUnitPrice)));
        if (target < policy.minimumLinePayableMinor() || target >= line.payableAmountMinor()) {
            throw new ServiceException("PRM-AMOUNT-013: 改价不得增价且不能低于授权底价", 422);
        }
        return Map.of(lineId, line.payableAmountMinor() - target);
    }

    private Map<String, Long> orderAmount(QuoteResult current, List<LineContext> contexts, long amountMinor) {
        if (amountMinor <= 0 || amountMinor > current.payableAmountMinor()) {
            throw new ServiceException("PRM-AMOUNT-014: 整单优惠超过当前应收", 422);
        }
        return allocate(current, contexts, amountMinor);
    }

    private Map<String, Long> orderPercent(QuoteResult current, List<LineContext> contexts, BigDecimal rate) {
        long amount = exactMinor(BigDecimal.valueOf(current.payableAmountMinor()).multiply(rate));
        if (amount <= 0 || amount > current.payableAmountMinor()) {
            throw new ServiceException("PRM-AMOUNT-014: 整单优惠超过当前应收", 422);
        }
        return allocate(current, contexts, amount);
    }

    private Map<String, Long> rounding(QuoteResult current, List<LineContext> contexts, Command command,
                                       Policy policy) {
        if (command.paymentMethod() != PaymentMethod.CASH || command.lineId() != null) {
            throw new ServiceException("PRM-AMOUNT-015: 仅现金整单允许抹零", 422);
        }
        long multiple = parseMinor(command.amountOrRate());
        if (!policy.roundingMultiplesMinor().contains(multiple)) {
            throw new ServiceException("PRM-AMOUNT-016: 抹零倍数不在策略白名单", 422);
        }
        long amount = current.payableAmountMinor() % multiple;
        if (amount <= 0 || amount > policy.maximumRoundingMinor()) {
            throw new ServiceException("PRM-AMOUNT-017: 抹零金额为零或超过绝对上限", 422);
        }
        return allocate(current, contexts, amount);
    }

    private Map<String, Long> allocate(QuoteResult current, List<LineContext> contexts, long amount) {
        Map<String, QuoteLine> lines = new LinkedHashMap<>();
        current.lines().forEach(line -> lines.put(line.lineId(), line));
        List<Weight> weights = contexts.stream().map(context -> new Weight(context.lineId(), context.lineNo(),
            context.skuId(), lines.get(context.lineId()).payableAmountMinor())).toList();
        return LargestRemainderAllocator.allocate(amount, weights);
    }

    private QuoteResult apply(QuoteResult current, Command command, Map<String, Long> allocations, long incremental) {
        List<QuoteLine> lines = current.lines().stream().map(line -> {
            long extra = allocations.getOrDefault(line.lineId(), 0L);
            return new QuoteLine(line.lineId(), line.grossAmountMinor(),
                Math.addExact(line.discountAmountMinor(), extra), line.payableAmountMinor() - extra);
        }).toList();
        List<AppliedAdjustment> adjustments = new ArrayList<>(current.adjustments());
        adjustments.add(new AppliedAdjustment(command.authorizationId(), incremental, allocations));
        List<Explanation> explanations = new ArrayList<>(current.explanations());
        explanations.add(new Explanation(command.authorizationId(), "APPLIED_MANUAL_" + command.actionType().name()));
        return new QuoteResult(current.grossAmountMinor(), Math.addExact(current.discountAmountMinor(), incremental),
            current.payableAmountMinor() - incremental, lines, current.appliedRuleIds(), explanations, adjustments);
    }

    private void requireCurrent(QuoteResult current, List<LineContext> contexts) {
        if (current == null || contexts == null || contexts.size() != current.lines().size()
            || current.grossAmountMinor() < 0 || current.discountAmountMinor() < 0 || current.payableAmountMinor() < 0
            || current.grossAmountMinor() != current.discountAmountMinor() + current.payableAmountMinor()) {
            throw new ServiceException("PRM-AMOUNT-018: 基础报价不守恒", 409);
        }
        Map<String, LineContext> unique = new LinkedHashMap<>();
        contexts.forEach(value -> unique.put(value.lineId(), value));
        if (unique.size() != contexts.size() || current.lines().stream().anyMatch(line -> !unique.containsKey(line.lineId())
            || line.grossAmountMinor() != line.discountAmountMinor() + line.payableAmountMinor())) {
            throw new ServiceException("PRM-AMOUNT-018: 基础报价不守恒", 409);
        }
    }

    private BigDecimal parseRate(String value) {
        try {
            BigDecimal rate = new BigDecimal(value);
            if (rate.scale() > 8 || rate.signum() <= 0 || rate.compareTo(BigDecimal.ONE) > 0) throw new ArithmeticException();
            return rate;
        } catch (RuntimeException exception) {
            throw new ServiceException("PRM-AMOUNT-019: 折扣率无效", 400);
        }
    }

    private long parseMinor(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || parsed > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return parsed;
        } catch (RuntimeException exception) {
            throw new ServiceException("PRM-AMOUNT-020: 分金额无效", 400);
        }
    }

    private long exactMinor(BigDecimal value) {
        try {
            long result = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (result < 0 || result > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return result;
        } catch (RuntimeException exception) {
            throw new ServiceException("PRM-AMOUNT-021: 金额计算越界", 422);
        }
    }

    private long sum(Iterable<Long> values) {
        long total = 0;
        try {
            for (long value : values) total = Math.addExact(total, value);
            if (total > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return total;
        } catch (ArithmeticException exception) {
            throw new ServiceException("PRM-AMOUNT-021: 金额计算越界", 422);
        }
    }
}
