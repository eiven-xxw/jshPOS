package com.jingshanghui.pos.returns.infrastructure.persistence.mapper;

import com.jingshanghui.pos.returns.infrastructure.persistence.ExchangePersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 换货 Saga 具名状态迁移和只追加关联 XML Mapper。 */
public interface ExchangeMapper {
    IdempotencyRow findIdempotency(@Param("tenantId") String tenantId,
                                   @Param("commandType") String commandType,
                                   @Param("idempotencyKey") String idempotencyKey);
    int insertIdempotency(IdempotencyWrite value);
    int insertExchange(ExchangeWrite value);
    int insertLeg(LegWrite value);
    int insertEvent(EventWrite value);
    int insertInbox(InboxWrite value);
    InboxRow findInbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
    int insertOutbox(OutboxWrite value);
    ExchangeRow findExchange(@Param("tenantId") String tenantId, @Param("exchangeId") String exchangeId);
    ExchangeRow lockExchange(@Param("tenantId") String tenantId, @Param("exchangeId") String exchangeId);
    List<LegRow> listLegs(@Param("tenantId") String tenantId, @Param("exchangeId") String exchangeId);

    int advance(@Param("tenantId") String tenantId, @Param("exchangeId") String exchangeId,
                @Param("expectedVersion") long expectedVersion,
                @Param("expectedStatus") String expectedStatus, @Param("nextStatus") String nextStatus,
                @Param("approverUserId") Long approverUserId,
                @Param("actualRefundAmountMinor") Long actualRefundAmountMinor,
                @Param("actualSaleReceivableMinor") Long actualSaleReceivableMinor,
                @Param("actualNewOrderSnapshotSha256") String actualNewOrderSnapshotSha256,
                @Param("updatedAt") LocalDateTime updatedAt);

    /** Saga 头只读投影；所有身份和冻结值均不可被状态更新覆盖。 */
    record ExchangeRow(String exchangeId, String requestSha256, String returnId, String originalOrderId,
                       String originalReturnCommandId, String newOrderId, String newSaleCommandId,
                       Long storeId, String terminalId, LocalDate businessDate, String currency,
                       long expectedRefundAmountMinor, Long actualRefundAmountMinor,
                       long expectedSaleReceivableMinor, Long actualSaleReceivableMinor,
                       String quoteFingerprint, String newSalePlanSha256,
                       String actualNewOrderSnapshotSha256, String status, Long requesterUserId,
                       Long approverUserId, String reasonCode, String correlationId,
                       long recordVersion, LocalDateTime updatedAt) { }

    /** 冻结腿只读投影。 */
    record LegRow(String legId, String legType, String ownerCode, String ownerAggregateId,
                  String ownerCommandId, long expectedAmountMinor, String frozenSha256) { }

    /** 创建幂等结果。 */
    record IdempotencyRow(String requestSha256, String exchangeId) { }

    /** 跨 Owner 观察 Inbox。 */
    record InboxRow(String eventId, String ownerCode, String aggregateId, String payloadSha256) { }
}
