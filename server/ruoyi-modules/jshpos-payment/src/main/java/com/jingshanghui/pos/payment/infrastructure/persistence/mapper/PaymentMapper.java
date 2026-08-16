package com.jingshanghui.pos.payment.infrastructure.persistence.mapper;

import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.IdempotencyView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.InternalFactView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ObservationView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReconciliationCaseView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundLineView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.ReservedQuantityView;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Gate 3A 支付持久化 Mapper。
 *
 * <p>SQL 统一位于 XML，所有查询显式携带可信 tenant_id 并列出字段。</p>
 */
public interface PaymentMapper {

    IdempotencyView findIdempotency(@Param("tenantId") String tenantId,
                                    @Param("commandType") String commandType,
                                    @Param("key") String key);

    int insertIdempotency(@Param("id") String id, @Param("tenantId") String tenantId,
                          @Param("commandType") String commandType, @Param("commandId") String commandId,
                          @Param("key") String key, @Param("requestHash") String requestHash,
                          @Param("aggregateId") String aggregateId, @Param("resultJson") String resultJson,
                          @Param("at") LocalDateTime at);

    int insertPayment(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId,
                      @Param("orderId") String orderId, @Param("storeId") Long storeId,
                      @Param("terminalId") String terminalId, @Param("amount") long amount,
                      @Param("currency") String currency, @Param("at") LocalDateTime at);

    PaymentView findPayment(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    PaymentView lockPayment(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    int countAttempts(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    int insertAttempt(@Param("tenantId") String tenantId, @Param("attemptId") String attemptId,
                      @Param("paymentId") String paymentId, @Param("providerCode") String providerCode,
                      @Param("providerRequestNo") String providerRequestNo, @Param("amount") long amount,
                      @Param("currency") String currency, @Param("at") LocalDateTime at);

    AttemptView findAttempt(@Param("tenantId") String tenantId, @Param("attemptId") String attemptId);

    AttemptView lockAttempt(@Param("tenantId") String tenantId, @Param("attemptId") String attemptId);

    AttemptView findSucceededAttempt(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    int updateAttemptStatus(@Param("tenantId") String tenantId, @Param("attemptId") String attemptId,
                            @Param("status") String status, @Param("providerTransactionNo") String providerTransactionNo,
                            @Param("version") long version);

    int updatePaymentStatus(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId,
                            @Param("status") String status, @Param("version") long version);

    int updatePaymentRefund(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId,
                            @Param("status") String status, @Param("succeededRefundMinor") long succeededRefundMinor,
                            @Param("version") long version);

    ObservationView findObservation(@Param("tenantId") String tenantId,
                                    @Param("observationId") String observationId);

    int insertObservation(@Param("tenantId") String tenantId, @Param("observationId") String observationId,
                          @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                          @Param("attemptId") String attemptId, @Param("source") String source,
                          @Param("observedStatus") String observedStatus, @Param("providerCode") String providerCode,
                          @Param("providerRequestNo") String providerRequestNo,
                          @Param("providerTransactionNo") String providerTransactionNo,
                          @Param("amount") long amount, @Param("currency") String currency,
                          @Param("payloadHash") String payloadHash, @Param("mergeResult") String mergeResult,
                          @Param("observedAt") LocalDateTime observedAt);

    int insertDeadLetter(@Param("tenantId") String tenantId, @Param("deadLetterId") String deadLetterId,
                         @Param("observationId") String observationId, @Param("aggregateType") String aggregateType,
                         @Param("aggregateId") String aggregateId, @Param("conflictType") String conflictType,
                         @Param("existingHash") String existingHash, @Param("receivedHash") String receivedHash,
                         @Param("reason") String reason, @Param("at") LocalDateTime at);

    int insertRefund(@Param("tenantId") String tenantId, @Param("refundId") String refundId,
                     @Param("paymentId") String paymentId, @Param("orderId") String orderId,
                     @Param("storeId") Long storeId, @Param("status") String status,
                     @Param("amount") long amount, @Param("currency") String currency,
                     @Param("reasonCode") String reasonCode, @Param("requesterId") Long requesterId,
                     @Param("providerCode") String providerCode, @Param("providerRequestNo") String providerRequestNo,
                     @Param("at") LocalDateTime at);

    int insertRefundLine(@Param("tenantId") String tenantId, @Param("refundLineId") String refundLineId,
                         @Param("refundId") String refundId, @Param("orderLineId") String orderLineId,
                         @Param("quantity") BigDecimal quantity, @Param("amount") long amount);

    RefundView findRefund(@Param("tenantId") String tenantId, @Param("refundId") String refundId);

    RefundView lockRefund(@Param("tenantId") String tenantId, @Param("refundId") String refundId);

    List<RefundLineView> findRefundLines(@Param("tenantId") String tenantId, @Param("refundId") String refundId);

    long sumReservedRefundAmount(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    List<ReservedQuantityView> findReservedQuantities(@Param("tenantId") String tenantId,
                                                      @Param("paymentId") String paymentId);

    long sumSucceededRefundAmount(@Param("tenantId") String tenantId, @Param("paymentId") String paymentId);

    int updateRefundStatus(@Param("tenantId") String tenantId, @Param("refundId") String refundId,
                           @Param("status") String status, @Param("approverId") Long approverId,
                           @Param("providerRefundNo") String providerRefundNo, @Param("version") long version);

    int insertHistory(@Param("tenantId") String tenantId, @Param("historyId") String historyId,
                      @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                      @Param("commandId") String commandId, @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus, @Param("version") long version,
                      @Param("actorId") Long actorId, @Param("reasonCode") String reasonCode,
                      @Param("at") LocalDateTime at);

    int insertAudit(@Param("tenantId") String tenantId, @Param("auditId") String auditId,
                    @Param("storeId") Long storeId, @Param("action") String action,
                    @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                    @Param("actorId") Long actorId, @Param("approverId") Long approverId,
                    @Param("commandId") String commandId, @Param("traceId") String traceId,
                    @Param("beforeStatus") String beforeStatus, @Param("afterStatus") String afterStatus,
                    @Param("amount") Long amount, @Param("currency") String currency,
                    @Param("requestHash") String requestHash, @Param("reasonCode") String reasonCode,
                    @Param("at") LocalDateTime at);

    int insertOutbox(@Param("tenantId") String tenantId, @Param("eventId") String eventId,
                     @Param("stream") String stream, @Param("eventType") String eventType,
                     @Param("aggregateType") String aggregateType, @Param("aggregateId") String aggregateId,
                     @Param("version") long version, @Param("correlationId") String correlationId,
                     @Param("payloadJson") String payloadJson, @Param("payloadHash") String payloadHash,
                     @Param("at") LocalDateTime at);

    int insertReconciliationRun(@Param("tenantId") String tenantId, @Param("runId") String runId,
                                @Param("providerCode") String providerCode, @Param("statementDate") LocalDate statementDate,
                                @Param("entryCount") int entryCount, @Param("actorId") Long actorId,
                                @Param("at") LocalDateTime at);

    int insertStatementEntry(@Param("tenantId") String tenantId, @Param("runId") String runId,
                             @Param("entryId") String entryId, @Param("providerCode") String providerCode,
                             @Param("providerTransactionNo") String providerTransactionNo,
                             @Param("businessType") String businessType, @Param("status") String status,
                             @Param("amount") long amount, @Param("currency") String currency,
                             @Param("occurredAt") LocalDateTime occurredAt, @Param("payloadHash") String payloadHash);

    List<InternalFactView> findInternalFactsByReference(@Param("tenantId") String tenantId,
                                                        @Param("providerCode") String providerCode,
                                                        @Param("reference") String reference);

    List<InternalFactView> findInternalFacts(@Param("tenantId") String tenantId,
                                             @Param("providerCode") String providerCode,
                                             @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    int insertReconciliationCase(@Param("tenantId") String tenantId, @Param("caseId") String caseId,
                                 @Param("runId") String runId, @Param("differenceType") String differenceType,
                                 @Param("internalReference") String internalReference,
                                 @Param("providerReference") String providerReference,
                                 @Param("at") LocalDateTime at);

    int completeReconciliationRun(@Param("tenantId") String tenantId, @Param("runId") String runId,
                                  @Param("caseCount") int caseCount);

    ReconciliationCaseView lockReconciliationCase(@Param("tenantId") String tenantId,
                                                   @Param("caseId") String caseId);

    int updateReconciliationCase(@Param("tenantId") String tenantId, @Param("caseId") String caseId,
                                 @Param("status") String status, @Param("resolverId") Long resolverId,
                                 @Param("approverId") Long approverId, @Param("resolutionCode") String resolutionCode,
                                 @Param("resolutionText") String resolutionText, @Param("version") long version);
}
