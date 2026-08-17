package com.jingshanghui.pos.transfer.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 调拨查询投影；不暴露租户列、内部成本摘要和库存写入口。 */
public final class TransferViews {
    private TransferViews() { }

    public record TransferHead(String transferId, Long sourceStoreId, String sourceWarehouseId,
                               Long destinationStoreId, String destinationWarehouseId, String status,
                               String reason, Long creatorUserId, Long approverUserId,
                               LocalDateTime approvedAt, LocalDateTime dispatchedAt,
                               LocalDateTime closedAt, long version) { }

    /** 调拨行同时冻结原申请单位、换算比例和基础数量，保证历史可解释。 */
    public record TransferLine(String transferLineId, String transferId, Long skuId,
                               Long requestedUnitId, long conversionNumerator, long conversionDenominator,
                               BigDecimal inputQuantity, Long baseUnitId, BigDecimal requestedQuantity,
                               BigDecimal dispatchedQuantity, BigDecimal receivedQuantity,
                               BigDecimal differenceQuantity) { }

    public record TransferDetail(TransferHead head, List<TransferLine> lines) {
        public TransferDetail { lines = List.copyOf(lines); }
    }

    /** 单行在途账本与在线投影的核对结果；仅报告差异，不直接修正任何历史事实。 */
    public record TransitLineReconciliation(String transferLineId, BigDecimal dispatchedLedgerQuantity,
                                             BigDecimal receivedLedgerQuantity,
                                             BigDecimal differenceLedgerQuantity,
                                             BigDecimal openTransitQuantity, boolean consistent) { }

    /** 调拨在途账本重算核对结果，供运维发现投影漂移并走受控修复。 */
    public record TransitReconciliation(String transferId, boolean consistent,
                                        List<TransitLineReconciliation> lines) {
        public TransitReconciliation { lines = List.copyOf(lines); }
    }

    public record DispatchHead(String dispatchId, String transferId, String sourceEventId,
                               String status, LocalDate businessDate, LocalDateTime postedAt) { }

    public record DispatchLine(String dispatchLineId, String dispatchId, String transferLineId,
                               Long skuId, Long baseUnitId, BigDecimal baseQuantity) { }

    public record ReceiptHead(String receiptId, String transferId, String sourceEventId,
                              String status, boolean finalReceipt, LocalDate businessDate,
                              LocalDateTime postedAt) { }

    public record ReceiptLine(String receiptLineId, String receiptId, String transferLineId,
                              String dispatchLineId, Long skuId, Long baseUnitId,
                              BigDecimal baseQuantity) { }
}
