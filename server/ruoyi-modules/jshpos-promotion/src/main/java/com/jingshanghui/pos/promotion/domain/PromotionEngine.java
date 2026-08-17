package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.LargestRemainderAllocator.Weight;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Predicate;

import static com.jingshanghui.pos.promotion.domain.PromotionModels.MAX_SAFE_MONEY_MINOR;

/** 版本 1.0 的纯函数促销引擎；相同输入必定产生相同输出。 */
public final class PromotionEngine {
    /** 供服务端、POS 和证据绑定的引擎版本。 */
    public static final String ENGINE_VERSION = "promotion-engine-1.0.0";

    /** 执行白名单规则并返回金额守恒结果。 */
    public QuoteResult quote(QuoteRequest request) {
        Map<String, MutableLine> state = new LinkedHashMap<>();
        Set<Integer> lineNumbers = new HashSet<>();
        for (BasketLine line : request.lines().stream().sorted(Comparator.comparingInt(BasketLine::lineNo)).toList()) {
            if (!lineNumbers.add(line.lineNo()) || state.containsKey(line.lineId())) {
                throw new ServiceException("PRM-ENGINE-003: 行号或行标识重复", 409);
            }
            state.put(line.lineId(), new MutableLine(line, lineAmount(line)));
        }
        List<RuleVersion> candidates = request.rules().stream()
            .sorted(Comparator.comparingInt(RuleVersion::priority).reversed()
                .thenComparing(RuleVersion::ruleVersionId)).toList();
        List<String> applied = new ArrayList<>();
        List<Explanation> explanations = new ArrayList<>();
        List<AppliedAdjustment> adjustments = new ArrayList<>();
        Map<String, String> bestByGroup = new HashMap<>();
        for (RuleVersion rule : candidates) {
            if (!rule.activeAt(request.businessTime())) {
                explanations.add(new Explanation(rule.ruleVersionId(), "TIME_NOT_MATCHED"));
                continue;
            }
            if (rule.stackMode() == StackMode.BEST_OF_GROUP) {
                String best = bestByGroup.computeIfAbsent(rule.exclusiveGroup(), ignored ->
                    selectBest(request, candidates, state, rule.exclusiveGroup()));
                if (!rule.ruleVersionId().equals(best)) {
                    explanations.add(new Explanation(rule.ruleVersionId(), "LOWER_BENEFIT"));
                    continue;
                }
            }
            List<MutableLine> eligible = state.values().stream()
                .filter(line -> !line.exclusiveLocked)
                .filter(line -> rule.scope().matches(line.source, request.storeId(), request.channel(),
                    request.businessTime())).toList();
            if (eligible.isEmpty() && state.values().stream().anyMatch(line -> line.exclusiveLocked
                && rule.scope().matches(line.source, request.storeId(), request.channel(), request.businessTime()))) {
                explanations.add(new Explanation(rule.ruleVersionId(), "EXCLUSIVE_CONFLICT"));
                continue;
            }
            Map<String, Long> before = new LinkedHashMap<>();
            eligible.forEach(line -> before.put(line.source.lineId(), line.discount));
            long discount = apply(rule, eligible, explanations);
            if (discount > 0) {
                applied.add(rule.ruleVersionId());
                explanations.add(new Explanation(rule.ruleVersionId(), "APPLIED"));
                Map<String, Long> allocations = new LinkedHashMap<>();
                eligible.forEach(line -> {
                    long delta = line.discount - before.getOrDefault(line.source.lineId(), 0L);
                    if (delta > 0) allocations.put(line.source.lineId(), delta);
                });
                adjustments.add(new AppliedAdjustment(rule.ruleVersionId(), discount, allocations));
                if (rule.stackMode() == StackMode.EXCLUSIVE) eligible.forEach(line -> line.exclusiveLocked = true);
            }
        }
        long gross = sum(state.values(), line -> line.gross);
        long discount = sum(state.values(), line -> line.discount);
        if (discount < 0 || discount > gross) throw new IllegalStateException("PRM-ENGINE-004: 优惠总额越界");
        List<QuoteLine> lines = state.values().stream().map(line -> new QuoteLine(line.source.lineId(),
            line.gross, line.discount, line.gross - line.discount)).toList();
        return new QuoteResult(gross, discount, gross - discount, lines, applied, explanations, adjustments);
    }

    private String selectBest(QuoteRequest request, List<RuleVersion> candidates,
                              Map<String, MutableLine> state, String group) {
        return candidates.stream().filter(rule -> rule.stackMode() == StackMode.BEST_OF_GROUP)
            .filter(rule -> Objects.equals(group, rule.exclusiveGroup()) && rule.activeAt(request.businessTime()))
            .map(rule -> Map.entry(rule, previewDiscount(rule, request, state)))
            .sorted(Comparator.<Map.Entry<RuleVersion, Long>>comparingLong(Map.Entry::getValue).reversed()
                .thenComparing(entry -> entry.getKey().ruleVersionId()))
            .map(entry -> entry.getKey().ruleVersionId()).findFirst().orElse("");
    }

    private long previewDiscount(RuleVersion rule, QuoteRequest request, Map<String, MutableLine> state) {
        List<MutableLine> copies = state.values().stream().filter(line -> !line.exclusiveLocked)
            .filter(line -> rule.scope().matches(line.source, request.storeId(), request.channel(),
                request.businessTime()))
            .map(MutableLine::copy).toList();
        return apply(rule, copies, new ArrayList<>());
    }

    private long apply(RuleVersion rule, List<MutableLine> lines, List<Explanation> explanations) {
        if (lines.isEmpty()) return 0;
        return switch (rule.ruleType()) {
            case SPECIAL_PRICE -> item(lines, line -> Math.max(0, current(line) - amount(
                BigDecimal.valueOf(requireNonNegative(rule.benefit().amountMinor(), "PRM-RULE-010"))
                    .multiply(line.source.quantity()))));
            case PERCENT_OFF -> item(lines, line -> amount(BigDecimal.valueOf(current(line))
                .multiply(requireRate(rule.benefit().discountRate()))));
            case AMOUNT_OFF -> item(lines, line -> Math.min(current(line),
                requireNonNegative(rule.benefit().amountMinor(), "PRM-RULE-011")));
            case NTH_ITEM_DISCOUNT -> nth(rule, lines);
            case THRESHOLD_AMOUNT_OFF -> thresholdAmount(rule, lines, explanations);
            case THRESHOLD_QUANTITY_OFF -> thresholdQuantity(rule, lines, explanations);
            case BUNDLE_PRICE -> bundle(rule, lines, explanations);
        };
    }

    private long nth(RuleVersion rule, List<MutableLine> lines) {
        int nth = rule.benefit().nth() == null ? 0 : rule.benefit().nth();
        if (nth < 2) throw new ServiceException("PRM-RULE-012: 第 N 件参数无效", 400);
        BigDecimal rate = requireRate(rule.benefit().discountRate());
        return item(lines, line -> {
            long discountedUnits = line.source.quantity().divideToIntegralValue(BigDecimal.valueOf(nth)).longValue();
            return Math.min(current(line), amount(BigDecimal.valueOf(line.source.unitPriceMinor())
                .multiply(BigDecimal.valueOf(discountedUnits)).multiply(rate)));
        });
    }

    private long thresholdAmount(RuleVersion rule, List<MutableLine> lines, List<Explanation> explanations) {
        long current = sum(lines, PromotionEngine::current);
        long threshold = requireNonNegative(rule.benefit().thresholdMinor(), "PRM-RULE-013");
        if (current < threshold) {
            explanations.add(new Explanation(rule.ruleVersionId(), "THRESHOLD_NOT_MET"));
            return 0;
        }
        return allocate(lines, Math.min(current,
            requireNonNegative(rule.benefit().amountMinor(), "PRM-RULE-014")));
    }

    private long thresholdQuantity(RuleVersion rule, List<MutableLine> lines, List<Explanation> explanations) {
        BigDecimal quantity = lines.stream().map(line -> line.source.quantity()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal threshold = rule.benefit().thresholdQuantity();
        if (threshold == null || threshold.signum() <= 0) throw new ServiceException("PRM-RULE-015: 数量门槛无效", 400);
        if (quantity.compareTo(threshold) < 0) {
            explanations.add(new Explanation(rule.ruleVersionId(), "THRESHOLD_NOT_MET"));
            return 0;
        }
        long current = sum(lines, PromotionEngine::current);
        return allocate(lines, Math.min(current,
            requireNonNegative(rule.benefit().amountMinor(), "PRM-RULE-016")));
    }

    private long bundle(RuleVersion rule, List<MutableLine> lines, List<Explanation> explanations) {
        List<BundleComponent> components = rule.benefit().bundleComponents();
        if (components.isEmpty()) throw new ServiceException("PRM-RULE-017: 组合组件不能为空", 400);
        long sets = Long.MAX_VALUE;
        List<MutableLine> selected = new ArrayList<>();
        for (BundleComponent component : components) {
            if (component.skuId() == null || component.quantity() == null || component.quantity().signum() <= 0) {
                throw new ServiceException("PRM-RULE-018: 组合组件无效", 400);
            }
            MutableLine line = lines.stream().filter(value -> component.skuId().equals(value.source.skuId()))
                .findFirst().orElse(null);
            if (line == null) {
                explanations.add(new Explanation(rule.ruleVersionId(), "BUNDLE_NOT_MET"));
                return 0;
            }
            sets = Math.min(sets, line.source.quantity().divideToIntegralValue(component.quantity()).longValue());
            selected.add(line);
        }
        if (sets <= 0) {
            explanations.add(new Explanation(rule.ruleVersionId(), "BUNDLE_NOT_MET"));
            return 0;
        }
        long current = sum(selected, PromotionEngine::current);
        long target = safeMultiply(requireNonNegative(rule.benefit().bundlePriceMinor(), "PRM-RULE-019"), sets);
        return allocate(selected, Math.max(0, current - Math.min(current, target)));
    }

    private long item(List<MutableLine> lines, java.util.function.ToLongFunction<MutableLine> calculator) {
        long total = 0;
        for (MutableLine line : lines) {
            long discount = Math.max(0, Math.min(current(line), calculator.applyAsLong(line)));
            line.discount = safeAdd(line.discount, discount);
            total = safeAdd(total, discount);
        }
        return total;
    }

    private long allocate(List<MutableLine> lines, long amountMinor) {
        if (amountMinor == 0) return 0;
        List<Weight> weights = lines.stream().filter(line -> current(line) > 0).map(line -> new Weight(
            line.source.lineId(), line.source.lineNo(), line.source.skuId(), current(line))).toList();
        if (weights.isEmpty()) return 0;
        Map<String, Long> allocated = LargestRemainderAllocator.allocate(amountMinor, weights);
        lines.forEach(line -> line.discount = safeAdd(line.discount,
            allocated.getOrDefault(line.source.lineId(), 0L)));
        return amountMinor;
    }

    private static long current(MutableLine line) {
        return line.gross - line.discount;
    }

    private static long lineAmount(BasketLine line) {
        return amount(BigDecimal.valueOf(line.unitPriceMinor()).multiply(line.quantity()));
    }

    private static long amount(BigDecimal value) {
        try {
            long result = value.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (result < 0 || result > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException exception) {
            throw new ServiceException("PRM-ENGINE-005: 金额超出精确安全范围", 400);
        }
    }

    private static BigDecimal requireRate(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0 || value.scale() > 8) {
            throw new ServiceException("PRM-RULE-020: 折扣率必须位于 0..1 且最多八位小数", 400);
        }
        return value;
    }

    private static long requireNonNegative(Long value, String code) {
        if (value == null || value < 0 || value > MAX_SAFE_MONEY_MINOR) {
            throw new ServiceException(code + ": 金额参数无效", 400);
        }
        return value;
    }

    private static <T> long sum(Collection<T> values, java.util.function.ToLongFunction<T> mapper) {
        long result = 0;
        for (T value : values) result = safeAdd(result, mapper.applyAsLong(value));
        return result;
    }

    private static long safeAdd(long left, long right) {
        try {
            long result = Math.addExact(left, right);
            if (result < 0 || result > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException exception) {
            throw new ServiceException("PRM-ENGINE-005: 金额超出精确安全范围", 400);
        }
    }

    private static long safeMultiply(long left, long right) {
        try {
            long result = Math.multiplyExact(left, right);
            if (result < 0 || result > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException exception) {
            throw new ServiceException("PRM-ENGINE-005: 金额超出精确安全范围", 400);
        }
    }

    private static final class MutableLine {
        private final BasketLine source;
        private final long gross;
        private long discount;
        private boolean exclusiveLocked;

        private MutableLine(BasketLine source, long gross) {
            this.source = source;
            this.gross = gross;
        }

        private MutableLine copy() {
            MutableLine copy = new MutableLine(source, gross);
            copy.discount = discount;
            copy.exclusiveLocked = exclusiveLocked;
            return copy;
        }
    }
}
