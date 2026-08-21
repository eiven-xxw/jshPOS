package com.jingshanghui.pos.returns.domain;

/** EXG-001 换货编排状态；每个状态都代表 Return Owner 已持久化的检查点。 */
public final class ExchangeStates {
    private ExchangeStates() { }

    /**
     * 换货只编排原退货和新销售两条权威业务腿，不表示第三笔资金或库存事实。
     * UNKNOWN 状态只能观察原 Owner 聚合，禁止创建替代命令。
     */
    public enum Status {
        DRAFT,
        APPROVED,
        RETURN_PENDING,
        RETURN_UNKNOWN,
        RETURN_COMPLETED,
        SALE_PENDING,
        SALE_UNKNOWN,
        COMPLETED,
        FAILED,
        MANUAL_RECOVERY_REQUIRED,
        CLOSED
    }

    /** 两条腿只保存冻结引用，不保存或覆盖 Owner 业务事实。 */
    public enum LegType { RETURN, SALE }
}
