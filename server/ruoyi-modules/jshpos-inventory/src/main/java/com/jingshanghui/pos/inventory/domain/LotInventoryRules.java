package com.jingshanghui.pos.inventory.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 批次库存的精确数量、FEFO 与日期失败关闭规则。
 *
 * <p>本规则只决定批次维度，不改变仓级库存总账与仓级移动加权成本。</p>
 */
public final class LotInventoryRules {
    public static final int QUANTITY_SCALE = 6;

    private LotInventoryRules() { }

    /** 将输入数量规范为六位小数，禁止静默舍入。 */
    public static BigDecimal exactQuantity(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) {
            throw new ServiceException("LOT-QUANTITY-001: " + field + " 必须大于零", 409);
        }
        try {
            return value.setScale(QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw new ServiceException("LOT-QUANTITY-002: " + field + " 精度超过六位小数", 409);
        }
    }

    /** 到期日当天仍可售，业务日晚于到期日时必须拒售。 */
    public static void requireSaleable(LocalDate expiryDate, LocalDate businessDate) {
        if (expiryDate == null || businessDate == null) {
            throw new ServiceException("LOT-DATE-001: 到期日或业务日缺失", 409);
        }
        if (businessDate.isAfter(expiryDate)) {
            throw new ServiceException("LOT-EXPIRED-001: 批次已过期，禁止销售", 409);
        }
    }

    /**
     * 按到期日、入库日、批次 ULID 稳定排序并分配，最后一批吸收精确余量。
     */
    public static List<Allocation> allocateFefo(List<Candidate> candidates, BigDecimal requested,
                                                LocalDate businessDate) {
        BigDecimal remaining = exactQuantity(requested, "requestedQuantity");
        List<Candidate> ordered = candidates == null ? List.of() : candidates.stream()
            .filter(candidate -> candidate.availableQuantity() != null && candidate.availableQuantity().signum() > 0)
            .sorted(Comparator.comparing(Candidate::expiryDate)
                .thenComparing(Candidate::receivedDate)
                .thenComparing(Candidate::lotId))
            .toList();
        List<Allocation> result = new ArrayList<>();
        for (Candidate candidate : ordered) {
            requireSaleable(candidate.expiryDate(), businessDate);
            BigDecimal available = exactQuantity(candidate.availableQuantity(), "availableQuantity");
            BigDecimal allocated = available.min(remaining).setScale(QUANTITY_SCALE);
            result.add(new Allocation(candidate.lotId(), allocated, candidate.policyVersionId(),
                candidate.expiryDate()));
            remaining = remaining.subtract(allocated).setScale(QUANTITY_SCALE);
            if (remaining.signum() == 0) return List.copyOf(result);
        }
        throw new ServiceException("LOT-BALANCE-001: 可售批次数量不足", 409);
    }

    /** FEFO 候选只包含 Inventory Owner 已锁定的批次余额。 */
    public record Candidate(String lotId, LocalDate receivedDate, LocalDate expiryDate,
                            BigDecimal availableQuantity, String policyVersionId) { }

    /** 成交时冻结的批次分配结果。 */
    public record Allocation(String lotId, BigDecimal quantity, String policyVersionId,
                             LocalDate expiryDate) { }
}
