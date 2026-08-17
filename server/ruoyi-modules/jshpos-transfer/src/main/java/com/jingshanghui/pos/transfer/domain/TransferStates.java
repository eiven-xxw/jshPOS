package com.jingshanghui.pos.transfer.domain;

/** 调拨状态、差异类型与在途流水类型的封闭枚举。 */
public final class TransferStates {
    private TransferStates() { }

    public enum Status {
        DRAFT, SUBMITTED, APPROVED, IN_TRANSIT, PARTIALLY_RECEIVED,
        DIFFERENCE_PENDING, CLOSED, CANCELLED
    }

    public enum DifferenceReason {
        SHORTAGE, DAMAGED, REJECTED, TRANSIT_LOSS
    }

    public enum TransitType {
        DISPATCHED, RECEIVED, DIFFERENCE_APPROVED
    }
}
