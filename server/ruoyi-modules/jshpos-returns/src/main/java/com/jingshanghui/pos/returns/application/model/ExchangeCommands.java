package com.jingshanghui.pos.returns.application.model;

import java.time.Instant;
import java.time.LocalDate;

/** EXG-001 应用命令；租户、操作者和数据范围只从可信服务端上下文取得。 */
public final class ExchangeCommands {
    private ExchangeCommands() { }

    /**
     * 创建换货 Saga 并冻结两条腿。
     * @param commandId 创建命令ULID
     * @param idempotencyKey 终端稳定幂等键
     * @param exchangeId 换货Saga ULID
     * @param returnId 既有原单退货退款ULID
     * @param originalOrderId 原成交订单ULID
     * @param originalReturnCommandId 必须复用的原退货命令ULID
     * @param newOrderId 新销售预分配订单ULID
     * @param newSaleCommandId 必须复用的新销售命令ULID
     * @param storeId 仅作与可信范围的一致性输入
     * @param terminalId 当前可信终端ULID
     * @param businessDate 新销售门店业务日
     * @param expectedRefundAmountMinor 原退货预期权威金额，单位分
     * @param expectedSaleReceivableMinor 新销售冻结应收，单位分
     * @param quoteFingerprint 新销售冻结报价SHA-256
     * @param newSalePlanSha256 新销售冻结计划SHA-256；成交后另存 Order Owner 权威快照摘要
     * @param reasonCode 换货原因码
     * @param correlationId 端到端关联ULID
     * @param occurredAt 创建发生UTC时间
     */
    public record CreateExchange(String commandId, String idempotencyKey, String exchangeId,
                                 String returnId, String originalOrderId, String originalReturnCommandId,
                                 String newOrderId, String newSaleCommandId, Long storeId,
                                 String terminalId, LocalDate businessDate,
                                 long expectedRefundAmountMinor, long expectedSaleReceivableMinor,
                                 String quoteFingerprint, String newSalePlanSha256,
                                 String reasonCode, String correlationId, Instant occurredAt) { }

    /** 独立审批换货关联；不代替原退货审批，也不创建销售命令。 */
    public record ApproveExchange(String commandId, String exchangeId, String reasonCode,
                                  String correlationId, Instant occurredAt) { }

    /** 受权恢复只选择原 RETURN 或 SALE 检查点，禁止传入新业务命令。 */
    public record RecoverExchange(String commandId, String exchangeId, String targetLeg,
                                  String reasonCode, String correlationId, Instant occurredAt) { }

    /** 跨 Owner 的只读观察；observationId 可重放，ownerCommandId 绝不重建。 */
    public record OwnerObservation(String observationId, String exchangeId, String ownerAggregateId,
                                   String ownerStatus, long amountMinor, String ownerSnapshotSha256,
                                   String payloadSha256, Instant observedAt) { }
}
