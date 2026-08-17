package com.jingshanghui.pos.transfer.application.model;

import java.math.BigDecimal;
import java.util.List;

/** 调拨应用命令；tenant_id 与操作者只能由可信上下文注入。 */
public final class TransferCommands {
    private TransferCommands() { }

    public record CreateTransfer(String transferId, Long sourceStoreId, String sourceWarehouseId,
                                 Long destinationStoreId, String destinationWarehouseId,
                                 List<CreateLine> lines, String reason, String correlationId) {
        public CreateTransfer { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record CreateLine(String transferLineId, Long skuId, Long unitId, BigDecimal requestedQuantity) { }

    public record StateCommand(String transferId, String commandId, long expectedVersion,
                               String reason, String correlationId) { }

    public record DispatchTransfer(String transferId, String dispatchId, String eventId,
                                   long expectedVersion, String correlationId) { }

    public record ReceiveTransfer(String transferId, String receiptId, String eventId,
                                  long expectedVersion, boolean finalReceipt,
                                  List<ReceiveLine> lines, String correlationId) {
        public ReceiveTransfer { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record ReceiveLine(String receiptLineId, String transferLineId, BigDecimal receivedQuantity) { }

    public record ResolveDifference(String transferId, String commandId, long expectedVersion,
                                    List<DifferenceLine> lines, String reason, String correlationId) {
        public ResolveDifference { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    public record DifferenceLine(String transferLineId, BigDecimal differenceQuantity, String differenceReason) { }
}
