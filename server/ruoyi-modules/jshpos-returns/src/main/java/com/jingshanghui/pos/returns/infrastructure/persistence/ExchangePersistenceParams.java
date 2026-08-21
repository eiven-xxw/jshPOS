package com.jingshanghui.pos.returns.infrastructure.persistence;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** EXG-001 XML Mapper 的具名不可变参数；不暴露通用更新或删除能力。 */
public final class ExchangePersistenceParams {
    private ExchangePersistenceParams() { }

    /** Saga 头冻结写入。 */
    public record ExchangeWrite(String exchangeId, String tenantId, String idempotencyKey,
                                String requestSha256, String returnId, String originalOrderId,
                                String originalReturnCommandId, String newOrderId, String newSaleCommandId,
                                Long storeId, String terminalId, LocalDate businessDate,
                                long expectedRefundAmountMinor, long expectedSaleReceivableMinor,
                                String quoteFingerprint, String newSalePlanSha256,
                                String reasonCode, Long requesterUserId, String correlationId,
                                LocalDateTime occurredAt) { }

    /** RETURN/SALE 两条冻结腿，只追加且不保存 Owner 可变状态。 */
    public record LegWrite(String legId, String tenantId, String exchangeId, String legType,
                           String ownerCode, String ownerAggregateId, String ownerCommandId,
                           long expectedAmountMinor, String frozenSha256, LocalDateTime createdAt) { }

    /** 每次状态迁移追加一条事件，包含来源观察摘要。 */
    public record EventWrite(String eventId, String tenantId, String exchangeId, String fromStatus,
                             String toStatus, String ownerCode, String ownerAggregateId,
                             String ownerEventId, String payloadSha256, long aggregateVersion,
                             Long actorUserId, String reasonCode, LocalDateTime occurredAt) { }

    /** 创建命令幂等绑定。 */
    public record IdempotencyWrite(String tenantId, String commandType, String idempotencyKey,
                                   String requestSha256, String exchangeId, LocalDateTime createdAt) { }

    /** Owner 观察 Inbox；同事件异内容必须隔离。 */
    public record InboxWrite(String eventId, String tenantId, String ownerCode, String aggregateId,
                             String payloadSha256, LocalDateTime receivedAt) { }

    /** 换货只追加事件复用 Return Owner 既有 Outbox。 */
    public record OutboxWrite(String eventId, String tenantId, String eventType, String aggregateId,
                              long aggregateVersion, String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime availableAt) { }
}
