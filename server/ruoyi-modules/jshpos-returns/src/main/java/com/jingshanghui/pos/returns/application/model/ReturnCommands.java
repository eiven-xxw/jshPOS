package com.jingshanghui.pos.returns.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Return Owner 应用命令；租户和操作者绝不由这些对象提供。 */
public final class ReturnCommands {
    private ReturnCommands() { }

    /**
     * 原单退货退款申请。
     * @param commandId 命令ULID @param idempotencyKey 稳定幂等键 @param returnId 退货退款ULID
     * @param orderId 原订单ULID @param storeId 门店一致性输入 @param terminalId 当前终端ULID
     * @param refundShiftId 现金退款班次；Provider无关退款仍保存操作班次
     * @param warehouseId 退货入库仓ULID @param businessDate 退货业务日
     * @param settlementKind CASH或PROVIDER_NEUTRAL @param paymentId 电子支付ULID；现金为空
     * @param reasonCode 原因码 @param lines 本次退货行 @param correlationId 端到端关联ULID
     * @param occurredAt 发生时间UTC
     */
    public record RequestReturn(String commandId, String idempotencyKey, String returnId, String orderId,
                                Long storeId, String terminalId, String refundShiftId, String warehouseId,
                                LocalDate businessDate, String settlementKind, String paymentId,
                                String reasonCode, List<RequestLine> lines,
                                String correlationId, Instant occurredAt) {
        public RequestReturn { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /** 原成交行与本次精确退货数量。 */
    public record RequestLine(String orderLineId, String quantity) { }

    /** 不落账的原单与退款金额预检；空 lines 返回零选择的完整可退投影。 */
    public record PreviewReturn(String orderQuery, List<RequestLine> lines) {
        public PreviewReturn { lines = lines == null ? List.of() : List.copyOf(lines); }
    }

    /** 独立审批人准入退款 Saga，不触发 Provider 网络。 */
    public record ApproveReturn(String commandId, String returnId, String reasonCode,
                                String correlationId, Instant occurredAt) { }

    /** Payment Owner 查询或可信回调形成的 Provider 无关观察。 */
    public record PaymentObservation(String observationId, String returnId, String paymentStatus,
                                     long amountMinor, String payloadSha256, Instant observedAt) { }
}
