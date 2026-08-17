package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PRM-003 金额守恒、累计上限、稳定舍入和损坏事实拒收测试。 */
class TransactionAllocationEngineTest {
    private static final String L1 = "01K5R000000000000000000001";
    private static final String L2 = "01K5R000000000000000000002";
    private final TransactionAllocationEngine engine = new TransactionAllocationEngine();

    @Test
    void freezesInStableOrderAndConservesTotals() {
        Snapshot result = engine.freeze(List.of(line(L2, 2, 102L, "2", 500, 0),
            line(L1, 1, 101L, "3", 1000, 101)));
        assertThat(result.lines()).extracting(SnapshotLine::lineId).containsExactly(L1, L2);
        assertThat(result.grossAmountMinor()).isEqualTo(1500);
        assertThat(result.discountAmountMinor()).isEqualTo(101);
        assertThat(result.payableAmountMinor()).isEqualTo(1399);
    }

    @Test
    void partialRefundUsesCumulativeTargetsAndFinalResidual() {
        Snapshot snapshot = snapshot();
        RefundResult first = engine.refund(snapshot, List.of(), List.of(request(L1, "1")));
        assertThat(first.lines().get(0)).extracting(RefundLine::grossAmountMinor,
            RefundLine::recoveredDiscountMinor, RefundLine::refundableAmountMinor).containsExactly(333L, 34L, 299L);
        PriorRefund prior = prior(first.lines().get(0));
        RefundResult second = engine.refund(snapshot, List.of(prior), List.of(request(L1, "1")));
        assertThat(second.lines().get(0)).extracting(RefundLine::grossAmountMinor,
            RefundLine::recoveredDiscountMinor, RefundLine::refundableAmountMinor).containsExactly(334L, 33L, 301L);
        RefundResult last = engine.refund(snapshot, List.of(prior(second.lines().get(0))), List.of(request(L1, "1")));
        assertThat(last.lines().get(0)).extracting(RefundLine::grossAmountMinor,
            RefundLine::recoveredDiscountMinor, RefundLine::refundableAmountMinor).containsExactly(333L, 34L, 299L);
        assertThat(last.lines().get(0).cumulativeDiscountAmountMinor()).isEqualTo(101);
    }

    @Test
    void supportsExactDecimalQuantity() {
        RefundResult result = engine.refund(snapshot(), List.of(), List.of(request(L2, "0.5")));
        assertThat(result.refundableAmountMinor()).isEqualTo(125);
        assertThat(result.lines().get(0).cumulativeQuantity()).isEqualByComparingTo("0.5");
    }

    @Test
    void rejectsDuplicateSnapshotAndRefundLines() {
        assertThatThrownBy(() -> engine.freeze(List.of(line(L1, 1, 1L, "1", 10, 1),
            line(L1, 2, 2L, "1", 10, 0)))).hasMessageContaining("重复");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(), List.of(request(L1, "1"), request(L1, "1"))))
            .hasMessageContaining("同次退款行重复");
    }

    @Test
    void rejectsOverRefundAndUnknownLine() {
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(), List.of(request(L1, "4"))))
            .hasMessageContaining("超过原成交数量");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(),
            List.of(request("01K5R000000000000000000099", "1")))).hasMessageContaining("不属于");
    }

    @Test
    void rejectsCorruptedHeaderAndHistory() {
        Snapshot bad = new Snapshot(1501, 101, 1400, snapshot().lines());
        assertThatThrownBy(() -> engine.refund(bad, List.of(), List.of(request(L1, "1"))))
            .hasMessageContaining("头行金额不一致");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(new PriorRefund(L1,
            new BigDecimal("1"), 333, 33, 300)), List.of(request(L1, "1"))))
            .hasMessageContaining("不符合原快照");
    }

    @Test
    void rejectsInvalidMoneyQuantityAndConservation() {
        assertThatThrownBy(() -> line(L1, 1, 1L, "0", 10, 0)).hasMessageContaining("数量");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, BigDecimal.ONE, 10, 11, 0))
            .hasMessageContaining("不守恒");
        assertThatThrownBy(() -> request(L1, "0.0000001")).hasMessageContaining("数量");
    }

    @Test
    void rejectsDuplicateOrOutOfBoundsHistory() {
        PriorRefund value = new PriorRefund(L1, BigDecimal.ZERO, 0, 0, 0);
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(value, value), List.of(request(L1, "1"))))
            .hasMessageContaining("累计退款行重复");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(new PriorRefund(L1,
            new BigDecimal("4"), 1000, 101, 899)), List.of(request(L2, "1"))))
            .hasMessageContaining("越界");
    }

    @Test
    void rejectsInvalidCountsIdentitiesHeadersAndNullHistorySafely() {
        assertThatThrownBy(() -> engine.freeze(null)).hasMessageContaining("1至500行");
        assertThatThrownBy(() -> engine.freeze(List.of())).hasMessageContaining("1至500行");
        assertThatThrownBy(() -> engine.freeze(java.util.Collections.nCopies(501,
            line(L1, 1, 1L, "1", 1, 0)))).hasMessageContaining("1至500行");
        assertThatThrownBy(() -> new SnapshotLine(L1, 0, 1L, BigDecimal.ONE, 1, 0, 1))
            .hasMessageContaining("标识");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, null, BigDecimal.ONE, 1, 0, 1))
            .hasMessageContaining("标识");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 0L, BigDecimal.ONE, 1, 0, 1))
            .hasMessageContaining("标识");
        assertThatThrownBy(() -> engine.freeze(List.of(line(L1, 1, 1L, "1", 1, 0),
            line(L2, 1, 2L, "1", 1, 0)))).hasMessageContaining("重复");
        assertThatThrownBy(() -> engine.refund(null, List.of(), List.of(request(L1, "1"))))
            .hasMessageContaining("1至500行");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(), null)).hasMessageContaining("1至500行");
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(), List.of())).hasMessageContaining("1至500行");
        assertThat(engine.refund(snapshot(), null, List.of(request(L1, "1"))).grossAmountMinor()).isEqualTo(333);
        Snapshot discountMismatch = new Snapshot(1500, 102, 1398, snapshot().lines());
        assertThatThrownBy(() -> engine.refund(discountMismatch, List.of(), List.of(request(L1, "1"))))
            .hasMessageContaining("头行金额");
        Snapshot payableMismatch = new Snapshot(1500, 101, 1398, snapshot().lines());
        assertThatThrownBy(() -> engine.refund(payableMismatch, List.of(), List.of(request(L1, "1"))))
            .hasMessageContaining("头行金额");
    }

    @Test
    void rejectsInvalidHistoryIdentityQuantityAndMoneyBounds() {
        assertThatThrownBy(() -> engine.refund(snapshot(), List.of(new PriorRefund(
            "01K5R000000000000000000099", BigDecimal.ZERO, 0, 0, 0)), List.of(request(L1, "1"))))
            .hasMessageContaining("越界");
        assertThatThrownBy(() -> new PriorRefund(L1, null, 0, 0, 0)).hasMessageContaining("数量");
        assertThatThrownBy(() -> new PriorRefund(L1, new BigDecimal("-1"), 0, 0, 0)).hasMessageContaining("数量");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, new BigDecimal("0.0000001"), 1, 0, 1))
            .hasMessageContaining("数量");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, new BigDecimal("12345678901234567890"), 1, 0, 1))
            .hasMessageContaining("数量");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, BigDecimal.ONE, -1, 0, 0))
            .hasMessageContaining("不守恒");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, BigDecimal.ONE, 10, -1, 11))
            .hasMessageContaining("不守恒");
        assertThatThrownBy(() -> new SnapshotLine(L1, 1, 1L, BigDecimal.ONE, 10, 10, -1))
            .hasMessageContaining("不守恒");
        long max = PromotionModels.MAX_SAFE_MONEY_MINOR;
        assertThatThrownBy(() -> engine.freeze(List.of(line(L1, 1, 1L, "1", max, 0),
            line(L2, 2, 2L, "1", 1, 0)))).hasMessageContaining("累计越界");
        assertThatThrownBy(() -> request(null, "1")).hasMessageContaining("ULID");
    }

    @Test
    void conservesAmountsForTenThousandFixedSeedRefundVectors() {
        Random random = new Random(0x5A20260817L);
        for (int index = 0; index < 10_000; index++) {
            int soldQuantity = random.nextInt(999) + 1;
            long gross = random.nextLong(1_000_000L) + 1;
            long discount = random.nextLong(gross + 1);
            Snapshot original = engine.freeze(List.of(line(L1, 1, 101L,
                Integer.toString(soldQuantity), gross, discount)));
            int refundQuantity = random.nextInt(soldQuantity) + 1;
            RefundResult result = engine.refund(original, List.of(),
                List.of(request(L1, Integer.toString(refundQuantity))));
            RefundLine refunded = result.lines().get(0);
            assertThat(result.grossAmountMinor()).isEqualTo(refunded.grossAmountMinor());
            assertThat(result.recoveredDiscountMinor()).isEqualTo(refunded.recoveredDiscountMinor());
            assertThat(result.refundableAmountMinor()).isEqualTo(refunded.refundableAmountMinor());
            assertThat(result.grossAmountMinor())
                .isEqualTo(result.recoveredDiscountMinor() + result.refundableAmountMinor());
            assertThat(refunded.cumulativeQuantity()).isLessThanOrEqualTo(original.lines().get(0).quantity());
        }
    }

    private Snapshot snapshot() { return engine.freeze(List.of(line(L1, 1, 101L, "3", 1000, 101),
        line(L2, 2, 102L, "2", 500, 0))); }
    private SnapshotLine line(String id, int no, long sku, String quantity, long gross, long discount) {
        return new SnapshotLine(id, no, sku, new BigDecimal(quantity), gross, discount, gross - discount);
    }
    private RefundRequestLine request(String id, String quantity) {
        return new RefundRequestLine(id, new BigDecimal(quantity));
    }
    private PriorRefund prior(RefundLine value) { return new PriorRefund(value.lineId(), value.cumulativeQuantity(),
        value.cumulativeGrossAmountMinor(), value.cumulativeDiscountAmountMinor(), value.cumulativePayableAmountMinor()); }
}
