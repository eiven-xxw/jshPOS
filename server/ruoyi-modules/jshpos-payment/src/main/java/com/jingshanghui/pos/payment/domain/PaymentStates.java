package com.jingshanghui.pos.payment.domain;

/** Gate 3A 支付、尝试、退款和对账案例的受控状态集合。 */
public final class PaymentStates {

    private PaymentStates() {
    }

    /** 支付意图状态；成功及退款态代表已确认资金事实，禁止回退。 */
    public enum PaymentStatus {
        /** 已创建但尚未形成任何渠道尝试。 */ CREATED,
        /** 原 attempt 已进入受理过程。 */ PROCESSING,
        /** 原 attempt 结果未知，只能查询、可信回调或对账收敛。 */ UNKNOWN,
        /** 已确认收款成功。 */ SUCCEEDED,
        /** 已明确失败，允许人工发起新的显式 attempt。 */ FAILED,
        /** 原 attempt 已取消。 */ CANCELLED,
        /** 支付意图已关闭，不允许继续扣款。 */ CLOSED,
        /** 成功支付已经发生部分成功退款。 */ PARTIALLY_REFUNDED,
        /** 成功支付的可退金额已经全部成功退款。 */ REFUNDED
    }

    /** 一次稳定 Provider 请求的状态；UNKNOWN 不允许替换 request 标识重试扣款。 */
    public enum AttemptStatus {
        /** 只创建本地请求事实，尚无外部副作用。 */ CREATED,
        /** Provider 已受理或正在处理。 */ PROCESSING,
        /** 请求可能已产生资金效果，但当前无法确认。 */ UNKNOWN,
        /** Provider 明确确认成功。 */ SUCCEEDED,
        /** Provider 明确确认失败。 */ FAILED,
        /** Provider 明确确认取消。 */ CANCELLED,
        /** Provider 明确确认关闭。 */ CLOSED
    }

    /** Provider 标准观察的来源；FAKE_TEST 只能出现在测试证据中。 */
    public enum ObservationSource { SYNC_RESPONSE, QUERY, CALLBACK, STATEMENT, FAKE_TEST }

    /** 退款状态；PROCESSING、UNKNOWN、SUCCEEDED 均占用原单可退额度。 */
    public enum RefundStatus {
        /** 退款申请已创建。 */ CREATED,
        /** 等待独立审批人确认。 */ PENDING_APPROVAL,
        /** 原退款请求正在处理。 */ PROCESSING,
        /** 原退款请求结果未知，继续占额。 */ UNKNOWN,
        /** 退款资金事实已确认成功。 */ SUCCEEDED,
        /** 退款明确失败，释放占额。 */ FAILED,
        /** 退款已取消，释放占额。 */ CANCELLED,
        /** 退款已关闭，释放占额。 */ CLOSED
    }

    /** 对账差异案例的受控生命周期。 */
    public enum ReconciliationStatus { OPEN, INVESTIGATING, WAITING_PROVIDER, RESOLVED, APPROVED, CLOSED }

    /** 内部事实和账单来源比较得到的稳定差异分类。 */
    public enum DifferenceType {
        INTERNAL_ONLY, PROVIDER_ONLY, AMOUNT_MISMATCH, CURRENCY_MISMATCH,
        STATUS_MISMATCH, DUPLICATE_PROVIDER_REF, REFUND_MISMATCH
    }
}
