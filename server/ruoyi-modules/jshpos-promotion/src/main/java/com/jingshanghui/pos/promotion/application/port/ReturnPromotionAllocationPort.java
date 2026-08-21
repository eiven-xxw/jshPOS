package com.jingshanghui.pos.promotion.application.port;

import java.math.BigDecimal;
import java.util.List;

/** Promotion Owner 对退货 Saga 暴露的原成交快照退款分摊端口。 */
public interface ReturnPromotionAllocationPort {

    AllocationResult allocate(AllocationCommand command);

    /** 只读预检，不生成退款标识、账本、审计或 Outbox。 */
    PreviewResult preview(PreviewCommand command);

    record PreviewCommand(String snapshotId, List<AllocationLine> lines) {
        public PreviewCommand { lines = List.copyOf(lines); }
    }

    record PreviewResult(String snapshotId, long grossAmountMinor,
                         long recoveredDiscountMinor, long refundableAmountMinor,
                         List<AllocatedLine> lines) {
        public PreviewResult { lines = List.copyOf(lines); }
    }

    /** @param eventId 稳定Owner命令ULID @param snapshotId 原成交促销快照ULID
     * @param refundId 退货退款ULID @param lines 本次退货数量 @param correlationId 关联ULID */
    record AllocationCommand(String eventId, String snapshotId, String refundId,
                             List<AllocationLine> lines, String correlationId) {
        public AllocationCommand { lines = List.copyOf(lines); }
    }

    /** 只声明原订单行和精确退货数量。 */
    record AllocationLine(String lineId, BigDecimal quantity) { }

    /** Promotion Owner 已保存的只追加退款恢复结果。 */
    record AllocationResult(String refundId, String snapshotId, long grossAmountMinor,
                            long recoveredDiscountMinor, long refundableAmountMinor,
                            List<AllocatedLine> lines) {
        public AllocationResult { lines = List.copyOf(lines); }
    }

    /** 行级本次金额与执行后累计上限。 */
    record AllocatedLine(String lineId, BigDecimal quantity, long grossAmountMinor,
                         long recoveredDiscountMinor, long refundableAmountMinor,
                         BigDecimal cumulativeQuantity, long cumulativePayableAmountMinor) { }
}
