package com.jingshanghui.pos.payment.domain;

/** Provider 无关组合支付使用的具名状态；外部 Provider 状态不得直接写入这些投影。 */
public final class TenderStates {

    private TenderStates() {
    }

    public enum PlanStatus {
        FROZEN, COLLECTING, UNKNOWN, PAID, FAILED, CANCELLED, MANUAL_RECOVERY_REQUIRED
    }

    public enum AllocationStatus {
        PLANNED, PROCESSING, UNKNOWN, SUCCEEDED, FAILED, CANCELLED
    }

    public enum TenderType {
        CASH, ELECTRONIC
    }
}
