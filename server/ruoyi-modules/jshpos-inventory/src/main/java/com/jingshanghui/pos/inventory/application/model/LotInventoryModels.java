package com.jingshanghui.pos.inventory.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 批次库存的受控命令、不可变快照与查询视图。 */
public final class LotInventoryModels {
    private LotInventoryModels() { }

    /** 所有批次命令共享的可信 Owner 来源。 */
    public record CommandSource(String eventId, String sourceType, String sourceId, String warehouseId,
                                Long storeId, LocalDate businessDate, String correlationId) { }

    /** 采购收货或期初库存建立批次。 */
    public record ReceiveCommand(CommandSource source, List<ReceiveLine> lines) {
        public ReceiveCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /** 入库行使用基础单位精确数量；到期日可显式给出或由已发布策略解析。 */
    public record ReceiveLine(String sourceLineId, Long skuId, Long baseUnitId, BigDecimal quantity,
                              String supplierLotCode, String internalLotCode, LocalDate productionDate,
                              LocalDate receivedDate, LocalDate explicitExpiryDate) { }

    /** 销售按 FEFO 自动选择批次。 */
    public record SaleCommand(CommandSource source, List<SaleLine> lines) {
        public SaleCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record SaleLine(String sourceLineId, Long skuId, Long baseUnitId, BigDecimal quantity) { }

    /** 原单退货必须引用原销售分配并按原批次恢复。 */
    public record ReturnCommand(CommandSource source, String originalOrderId, List<ReturnLine> lines) {
        public ReturnCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record ReturnLine(String sourceLineId, String originalOrderLineId, Long skuId,
                             Long baseUnitId, BigDecimal quantity) { }

    /** 盘点、采购退货和调拨由各 Owner 显式携带已验证批次。 */
    public record ExplicitCommand(CommandSource source, String movementType, List<ExplicitLine> lines) {
        public ExplicitCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record ExplicitLine(String sourceLineId, String lotId, Long skuId, Long baseUnitId,
                               BigDecimal quantity, String movementType) {
        /** 单一方向命令的兼容构造器；方向由命令级 movementType 提供。 */
        public ExplicitLine(String sourceLineId, String lotId, Long skuId, Long baseUnitId,
                            BigDecimal quantity) {
            this(sourceLineId, lotId, skuId, baseUnitId, quantity, null);
        }
    }

    /** 调拨收货按发出批次身份在目的仓建立或复用批次，并保留原分配引用。 */
    public record TransferReceiveCommand(CommandSource source, String dispatchId,
                                         List<TransferReceiveLine> lines) {
        public TransferReceiveCommand { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record TransferReceiveLine(String receiptLineId, String dispatchLineId, String sourceLotId,
                                      Long skuId, Long baseUnitId, BigDecimal quantity) { }

    /** 幂等命令结果。 */
    public record ApplyResult(String eventId, String status, int affectedLines, String requestSha256,
                              List<AllocationView> allocations) {
        public ApplyResult { allocations = allocations == null ? List.of() : List.copyOf(allocations); }
    }

    /** 成交/退货冻结的批次分配。 */
    public record AllocationView(String allocationId, String sourceId, String sourceLineId, String lotId,
                                 Long skuId, BigDecimal quantity, String allocationType,
                                 String policyVersionId, LocalDate expiryDate) { }

    /** 批次余额与效期查询视图。 */
    public record LotView(String lotId, Long storeId, String warehouseId, Long skuId, Long baseUnitId,
                          String supplierLotCode, String internalLotCode, LocalDate productionDate,
                          LocalDate receivedDate, LocalDate expiryDate, String policyVersionId,
                          int nearExpiryDays, BigDecimal onHandQuantity, long lastLedgerSequence, String expiryStatus,
                          LocalDateTime updatedAt) { }

    /** 已执行命令的幂等视图。 */
    public record CommandView(String eventId, String requestSha256, String status, int affectedLines,
                              LocalDateTime appliedAt) { }

    /** 仓库总账中对应来源事件/行的权威数量摘要。 */
    public record GenericMovementView(String movementType, BigDecimal absoluteQuantity) { }

    /** 批次投影重建命令，命令 ID 同时是稳定幂等键。 */
    public record RebuildCommand(String commandId, Long storeId, String warehouseId, Long skuId,
                                 LocalDate businessDate, String correlationId) { }

    /** 从只追加流水重建后的守恒摘要。 */
    public record RebuildResult(String commandId, int lotCount, BigDecimal ledgerQuantity,
                                BigDecimal projectedQuantity, boolean changed, String requestSha256) { }

    /** 单批次只追加流水的聚合投影。 */
    public record LedgerProjection(BigDecimal ledgerQuantity, long lastLedgerSequence) { }

    /** 不可变批次数据包发布记录；版本按可信租户、门店和仓库单调递增。 */
    public record LotPackageRelease(String releaseId, long packageVersion, long previousVersion,
                                    String sourceSha256, byte[] payloadBytes, String payloadSha256,
                                    String signingKeyId, byte[] signatureBytes, int recordCount,
                                    LocalDateTime generatedAt) { }
}
