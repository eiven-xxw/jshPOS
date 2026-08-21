package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Order/Sync 编排向 Promotion Owner 提交 POS 已冻结成交快照的版本化端口。 */
public interface PromotionSnapshotIngestionPort {
    void ingest(SnapshotCommand command);

    /** 命令不包含 tenant_id；租户与操作者只能由可信服务端上下文提供。 */
    record SnapshotCommand(String sourceEventId, String correlationId, String quoteId, String snapshotId,
                           String orderId, Long storeId, String terminalId, LocalDate businessDate,
                           long packageVersion, String engineVersion, String quoteFingerprint,
                           String snapshotSha256, long grossAmountMinor, long discountAmountMinor,
                           long payableAmountMinor, Instant occurredAt, List<SnapshotLine> lines) {
        public SnapshotCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /** 成交行保留精确数量、冻结单价和优惠来源分摊，不允许服务端重新计算优惠。 */
    record SnapshotLine(String lineId, int lineNo, Long skuId, BigDecimal quantity, long unitPriceMinor,
                        long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                        Map<String, Long> sourceAllocations) {
        public SnapshotLine {
            sourceAllocations = sourceAllocations == null ? Map.of() : Map.copyOf(sourceAllocations);
        }
    }
}
