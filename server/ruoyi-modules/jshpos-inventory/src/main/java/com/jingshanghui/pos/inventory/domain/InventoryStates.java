package com.jingshanghui.pos.inventory.domain;

/** Gate 4A 库存移动、负库存策略和命令状态的封闭枚举。 */
public final class InventoryStates {

    private InventoryStates() {
    }

    public enum MovementType {
        /** 已完成销售的可售库存出库。 */
        SALE_OUT,
        /** 已成功原单退款对应的可售库存退货入库。 */
        SALE_RETURN_IN
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
