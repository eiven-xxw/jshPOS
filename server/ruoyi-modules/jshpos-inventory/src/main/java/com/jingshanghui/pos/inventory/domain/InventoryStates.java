package com.jingshanghui.pos.inventory.domain;

/** 库存移动、负库存策略和命令状态的封闭枚举。 */
public final class InventoryStates {

    private InventoryStates() {
    }

    public enum MovementType {
        /** 已完成销售的可售库存出库。 */
        SALE_OUT,
        /** 已成功原单退款对应的可售库存退货入库。 */
        SALE_RETURN_IN,
        /** 盘点确认的盘盈入库。 */
        STOCKTAKE_GAIN,
        /** 盘点确认的盘亏出库。 */
        STOCKTAKE_LOSS,
        /** 已确认采购收货的入库。 */
        PURCHASE_RECEIPT_IN,
        /** 已批准原收货采购退货的出库。 */
        PURCHASE_RETURN_OUT,
        /** 已审批调拨从来源仓发出的出库。 */
        TRANSFER_OUT,
        /** 已发出调拨在目的仓确认收货的入库。 */
        TRANSFER_IN
    }

    public enum NegativeStockMode {
        /** 可用量不足时整个库存命令失败。 */
        DENY,
        /** 预留给后续主管权限设计，本 Gate 不允许执行。 */
        ALLOW_WITH_PERMISSION,
        /** 允许真实负数，但必须产生异常和审计。 */
        ALLOW_AND_ALERT
    }

    public enum CommandStatus {
        PROCESSING, APPLIED
    }
}
