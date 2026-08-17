package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jingshanghui.pos.promotion.domain.PromotionModels.MAX_SAFE_MONEY_MINOR;

/** 金额守恒的最大余数分摊器。 */
public final class LargestRemainderAllocator {
    private LargestRemainderAllocator() {
    }

    /**
     * 以权重分摊整数金额，余数按 remainder desc、lineNo、skuId、lineId 决定。
     *
     * @param amountMinor 待分摊金额
     * @param weights 稳定权重
     * @return 以 lineId 为键的守恒结果
     */
    public static Map<String, Long> allocate(long amountMinor, List<Weight> weights) {
        if (amountMinor < 0 || weights == null || weights.isEmpty()) {
            throw new ServiceException("PRM-ALLOC-001: 分摊输入无效", 400);
        }
        long totalWeight = 0;
        for (Weight weight : weights) {
            if (weight.amountMinor() < 0 || weight.amountMinor() > MAX_SAFE_MONEY_MINOR) {
                throw new ServiceException("PRM-ALLOC-003: 分摊权重不能为负或越界", 400);
            }
            try {
                totalWeight = Math.addExact(totalWeight, weight.amountMinor());
                if (totalWeight > MAX_SAFE_MONEY_MINOR) throw new ArithmeticException();
            } catch (ArithmeticException exception) {
                throw new ServiceException("PRM-ALLOC-005: 分摊权重总额越界", 400);
            }
        }
        if (amountMinor > totalWeight || totalWeight <= 0) {
            throw new ServiceException("PRM-ALLOC-002: 分摊金额超过可分摊基数", 400);
        }
        BigInteger total = BigInteger.valueOf(totalWeight);
        List<Share> shares = new ArrayList<>();
        long assigned = 0;
        for (Weight weight : weights) {
            BigInteger[] divided = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(weight.amountMinor()))
                .divideAndRemainder(total);
            long floor = divided[0].longValueExact();
            assigned = Math.addExact(assigned, floor);
            shares.add(new Share(weight, floor, divided[1]));
        }
        shares.sort(Comparator.comparing(Share::remainder).reversed()
            .thenComparingInt(value -> value.weight().lineNo())
            .thenComparing(value -> value.weight().skuId())
            .thenComparing(value -> value.weight().lineId()));
        long remaining = amountMinor - assigned;
        for (int index = 0; index < remaining; index++) {
            Share current = shares.get(index % shares.size());
            shares.set(index % shares.size(), new Share(current.weight(), current.amountMinor() + 1,
                current.remainder()));
        }
        shares.sort(Comparator.comparingInt(value -> value.weight().lineNo()));
        Map<String, Long> result = new LinkedHashMap<>();
        shares.forEach(share -> result.put(share.weight().lineId(), share.amountMinor()));
        long check = 0;
        for (long value : result.values()) check = Math.addExact(check, value);
        if (check != amountMinor) {
            throw new IllegalStateException("PRM-ALLOC-004: 分摊金额不守恒");
        }
        return result;
    }

    /**
     * 分摊权重。
     *
     * @param lineId 行标识
     * @param lineNo 行号
     * @param skuId SKU
     * @param amountMinor 可分摊金额
     */
    public record Weight(String lineId, int lineNo, Long skuId, long amountMinor) {
    }

    private record Share(Weight weight, long amountMinor, BigInteger remainder) {
    }
}
