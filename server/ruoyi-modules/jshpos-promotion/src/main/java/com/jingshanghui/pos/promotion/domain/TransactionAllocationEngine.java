package com.jingshanghui.pos.promotion.domain;

import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jingshanghui.pos.promotion.domain.PromotionModels.MAX_SAFE_MONEY_MINOR;

/**
 * PRM-003 成交优惠快照与原快照退款恢复纯领域引擎。
 *
 * <p>金额始终使用最小货币单位整数；数量使用最多六位小数的 {@link BigDecimal}。
 * 部分退款按累计数量计算目标累计金额，最后一次退完该行时吸收全部舍入余数。</p>
 */
public final class TransactionAllocationEngine {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";

    /** 不可变成交行输入，同时也是退款计算的唯一金额来源。 */
    public record SnapshotLine(String lineId, int lineNo, Long skuId, BigDecimal quantity,
                               long grossAmountMinor, long discountAmountMinor, long payableAmountMinor) {
        public SnapshotLine {
            requireUlid(lineId, "成交行");
            if (lineNo <= 0 || skuId == null || skuId <= 0) fail("PRM-SNAPSHOT-001: 成交行标识无效", 400);
            quantity = requireQuantity(quantity, true, "成交数量");
            requireConserved(grossAmountMinor, discountAmountMinor, payableAmountMinor, "成交行");
        }
    }

    /** 守恒且按稳定行号排序的成交快照。 */
    public record Snapshot(long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                           List<SnapshotLine> lines) {
        public Snapshot { lines = List.copyOf(lines); }
    }

    /** 某行在本次退款前的累计恢复事实。 */
    public record PriorRefund(String lineId, BigDecimal quantity, long grossAmountMinor,
                              long discountAmountMinor, long payableAmountMinor) {
        public PriorRefund {
            requireUlid(lineId, "累计退款行");
            quantity = requireQuantity(quantity, false, "累计退款数量");
            requireConserved(grossAmountMinor, discountAmountMinor, payableAmountMinor, "累计退款行");
        }
    }

    /** 本次请求退回的原成交行数量。 */
    public record RefundRequestLine(String lineId, BigDecimal quantity) {
        public RefundRequestLine {
            requireUlid(lineId, "退款行");
            quantity = requireQuantity(quantity, true, "退款数量");
        }
    }

    /** 本次行退款及执行后的累计上限快照。 */
    public record RefundLine(String lineId, BigDecimal quantity, long grossAmountMinor,
                             long recoveredDiscountMinor, long refundableAmountMinor,
                             BigDecimal cumulativeQuantity, long cumulativeGrossAmountMinor,
                             long cumulativeDiscountAmountMinor, long cumulativePayableAmountMinor) { }

    /** 本次退款恢复的守恒结果。 */
    public record RefundResult(long grossAmountMinor, long recoveredDiscountMinor,
                               long refundableAmountMinor, List<RefundLine> lines) {
        public RefundResult { lines = List.copyOf(lines); }
    }

    /** 冻结行顺序并校验整单与逐行金额守恒。 */
    public Snapshot freeze(List<SnapshotLine> source) {
        if (source == null || source.isEmpty() || source.size() > 500) {
            fail("PRM-SNAPSHOT-002: 成交快照必须包含1至500行", 400);
        }
        List<SnapshotLine> lines = source.stream().sorted(Comparator.comparingInt(SnapshotLine::lineNo)
            .thenComparing(SnapshotLine::skuId).thenComparing(SnapshotLine::lineId)).toList();
        Map<String, Boolean> lineIds = new LinkedHashMap<>();
        Map<Integer, Boolean> lineNos = new LinkedHashMap<>();
        long gross = 0; long discount = 0; long payable = 0;
        for (SnapshotLine line : lines) {
            if (lineIds.put(line.lineId(), Boolean.TRUE) != null || lineNos.put(line.lineNo(), Boolean.TRUE) != null) {
                fail("PRM-SNAPSHOT-003: 成交行标识或行号重复", 409);
            }
            gross = add(gross, line.grossAmountMinor());
            discount = add(discount, line.discountAmountMinor());
            payable = add(payable, line.payableAmountMinor());
        }
        requireConserved(gross, discount, payable, "成交快照");
        return new Snapshot(gross, discount, payable, lines);
    }

    /**
     * 以原快照为基准计算本次退款；拒绝超数量、超金额、损坏累计事实和重复行。
     */
    public RefundResult refund(Snapshot snapshot, List<PriorRefund> history, List<RefundRequestLine> requests) {
        if (snapshot == null || requests == null || requests.isEmpty() || requests.size() > 500) {
            fail("PRM-REFUND-001: 退款请求必须包含1至500行", 400);
        }
        Snapshot verified = freeze(snapshot.lines());
        if (verified.grossAmountMinor() != snapshot.grossAmountMinor()
            || verified.discountAmountMinor() != snapshot.discountAmountMinor()
            || verified.payableAmountMinor() != snapshot.payableAmountMinor()) {
            fail("PRM-SNAPSHOT-004: 成交快照头行金额不一致", 500);
        }
        Map<String, SnapshotLine> originals = new LinkedHashMap<>();
        verified.lines().forEach(line -> originals.put(line.lineId(), line));
        Map<String, PriorRefund> priors = validateHistory(originals, history == null ? List.of() : history);
        Map<String, Boolean> requested = new LinkedHashMap<>();
        List<RefundLine> lines = new ArrayList<>();
        long gross = 0; long discount = 0; long payable = 0;
        for (RefundRequestLine request : requests) {
            if (requested.put(request.lineId(), Boolean.TRUE) != null) {
                fail("PRM-REFUND-002: 同次退款行重复", 409);
            }
            SnapshotLine original = originals.get(request.lineId());
            if (original == null) fail("PRM-REFUND-003: 退款行不属于原成交快照", 404);
            PriorRefund prior = priors.getOrDefault(request.lineId(), zero(request.lineId()));
            BigDecimal cumulativeQuantity = exactQuantity(prior.quantity().add(request.quantity()));
            if (cumulativeQuantity.compareTo(original.quantity()) > 0) {
                fail("PRM-REFUND-004: 累计退款数量超过原成交数量", 409);
            }
            Amounts target = target(original, cumulativeQuantity);
            long lineGross = subtract(target.gross(), prior.grossAmountMinor());
            long lineDiscount = subtract(target.discount(), prior.discountAmountMinor());
            long linePayable = subtract(target.payable(), prior.payableAmountMinor());
            requireConserved(lineGross, lineDiscount, linePayable, "本次退款行");
            lines.add(new RefundLine(request.lineId(), request.quantity(), lineGross, lineDiscount, linePayable,
                cumulativeQuantity, target.gross(), target.discount(), target.payable()));
            gross = add(gross, lineGross); discount = add(discount, lineDiscount); payable = add(payable, linePayable);
        }
        requireConserved(gross, discount, payable, "本次退款");
        return new RefundResult(gross, discount, payable, lines);
    }

    private Map<String, PriorRefund> validateHistory(Map<String, SnapshotLine> originals, List<PriorRefund> history) {
        Map<String, PriorRefund> result = new LinkedHashMap<>();
        for (PriorRefund prior : history) {
            if (result.put(prior.lineId(), prior) != null) fail("PRM-REFUND-005: 累计退款行重复", 500);
            SnapshotLine original = originals.get(prior.lineId());
            if (original == null || prior.quantity().compareTo(original.quantity()) > 0) {
                fail("PRM-REFUND-006: 累计退款事实越界", 500);
            }
            Amounts expected = target(original, prior.quantity());
            if (expected.gross() != prior.grossAmountMinor() || expected.discount() != prior.discountAmountMinor()
                || expected.payable() != prior.payableAmountMinor()) {
                fail("PRM-REFUND-007: 累计退款事实不符合原快照", 500);
            }
        }
        return result;
    }

    private Amounts target(SnapshotLine original, BigDecimal cumulativeQuantity) {
        if (cumulativeQuantity.signum() == 0) return new Amounts(0, 0, 0);
        if (cumulativeQuantity.compareTo(original.quantity()) == 0) {
            return new Amounts(original.grossAmountMinor(), original.discountAmountMinor(), original.payableAmountMinor());
        }
        long gross = proportional(original.grossAmountMinor(), cumulativeQuantity, original.quantity());
        long discount = proportional(original.discountAmountMinor(), cumulativeQuantity, original.quantity());
        if (discount > gross) fail("PRM-REFUND-008: 比例舍入后优惠超过原金额", 500);
        return new Amounts(gross, discount, gross - discount);
    }

    private long proportional(long total, BigDecimal cumulative, BigDecimal original) {
        try {
            return BigDecimal.valueOf(total).multiply(cumulative).divide(original, 0, RoundingMode.HALF_UP)
                .longValueExact();
        } catch (ArithmeticException exception) {
            fail("PRM-REFUND-009: 退款比例计算越界", 422);
            return 0;
        }
    }

    private static PriorRefund zero(String lineId) { return new PriorRefund(lineId, BigDecimal.ZERO, 0, 0, 0); }

    private static BigDecimal requireQuantity(BigDecimal value, boolean positive, String field) {
        if (value == null || (positive ? value.signum() <= 0 : value.signum() < 0)
            || value.scale() > 6 || value.precision() > 19) {
            fail("PRM-QUANTITY-001: " + field + "无效", 400);
        }
        return exactQuantity(value);
    }

    private static BigDecimal exactQuantity(BigDecimal value) {
        try { return value.setScale(6, RoundingMode.UNNECESSARY).stripTrailingZeros(); }
        catch (ArithmeticException exception) { fail("PRM-QUANTITY-001: 数量精度超过六位", 400); return BigDecimal.ZERO; }
    }

    private static void requireConserved(long gross, long discount, long payable, String field) {
        if (!money(gross) || !money(discount) || !money(payable) || gross != add(discount, payable)) {
            fail("PRM-AMOUNT-030: " + field + "金额不守恒", 409);
        }
    }

    private static boolean money(long value) { return value >= 0 && value <= MAX_SAFE_MONEY_MINOR; }

    private static long add(long left, long right) {
        try {
            long value = Math.addExact(left, right);
            if (!money(value)) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException exception) {
            fail("PRM-AMOUNT-031: 金额累计越界", 422);
            return 0;
        }
    }

    private static long subtract(long left, long right) {
        try {
            long value = Math.subtractExact(left, right);
            if (!money(value)) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException exception) {
            fail("PRM-REFUND-010: 退款增量出现倒退或越界", 500);
            return 0;
        }
    }

    private static void requireUlid(String value, String field) {
        if (value == null || !value.matches(ULID)) fail("PRM-INPUT-001: " + field + "ULID无效", 400);
    }

    private static void fail(String message, int code) { throw new ServiceException(message, code); }

    private record Amounts(long gross, long discount, long payable) { }
}
