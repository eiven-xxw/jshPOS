package com.jingshanghui.pos.returns.domain;

/** Gate 5B 退货退款状态与结算方式。 */
public final class ReturnStates {
    private ReturnStates() { }

    /** 每个状态都代表已经持久化的 Saga 检查点。 */
    public enum Status {
        PENDING_APPROVAL,
        PROMOTION_PENDING,
        CASH_REFUND_PENDING,
        PAYMENT_PENDING,
        PAYMENT_UNKNOWN,
        INVENTORY_PENDING,
        COMPLETED,
        FAILED
    }

    /** 现金由 Order Owner 落账；电子支付只调用既有 Provider 无关核心。 */
    public enum SettlementKind { CASH, PROVIDER_NEUTRAL }
}
