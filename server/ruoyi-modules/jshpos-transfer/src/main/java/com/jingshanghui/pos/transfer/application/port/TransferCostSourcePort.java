package com.jingshanghui.pos.transfer.application.port;

import java.math.BigDecimal;

/** 成本 Owner 读取已入账调拨发出/收货事实的受控端口；不接受 tenant_id 或调用方成本。 */
public interface TransferCostSourcePort {
    DispatchCostSource requireDispatchLine(String dispatchLineId);
    ReceiptCostSource requireReceiptLine(String receiptLineId);

    /** 来源仓发出事实，成本 Owner 将为其冻结发出成本快照。 */
    record DispatchCostSource(String dispatchLineId, String dispatchId, String transferId,
                              String sourceWarehouseId, String destinationWarehouseId,
                              Long skuId, Long baseUnitId, BigDecimal baseQuantity,
                              String currencyCode) { }

    /** 目的仓收货事实及不可变原发出行，用于继承来源仓成本快照。 */
    record ReceiptCostSource(String receiptLineId, String receiptId, String dispatchLineId,
                             String sourceWarehouseId, String destinationWarehouseId,
                             Long skuId, Long baseUnitId, BigDecimal baseQuantity,
                             String currencyCode) { }
}
