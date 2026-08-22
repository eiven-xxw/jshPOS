package com.jingshanghui.pos.procurement.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Replenishment Mapper 写入参数；tenantId 只能由可信应用服务填充。 */
public final class ReplenishmentPersistenceParams {

    private ReplenishmentPersistenceParams() {
    }

    public record PolicyWrite(String policyVersionId, String tenantId, Long storeId,
                              String warehouseId, int versionNo, LocalDateTime effectiveFrom,
                              String idempotencyKey, String requestSha256,
                              Long actorUserId, LocalDateTime at) {
    }

    public record PolicyItemWrite(String policyItemId, String tenantId, String policyVersionId,
                                  Long skuId, String skuCode, Long baseUnitId,
                                  Long purchaseUnitId, long conversionNumerator,
                                  long conversionDenominator, String supplierId,
                                  BigDecimal minimumBaseQuantity, BigDecimal maximumBaseQuantity,
                                  BigDecimal minimumOrderQuantity, BigDecimal orderMultiple,
                                  boolean includeConfirmedInTransit, long unitPriceMinor,
                                  int taxRateBps, String itemSha256, LocalDateTime at) {
    }

    public record PolicyStateUpdate(String tenantId, String policyVersionId,
                                    String expectedState, String nextState,
                                    long expectedVersion, String contentSha256,
                                    LocalDateTime at) {
    }

    public record RunWrite(String generationRunId, String tenantId, String policyVersionId,
                           Long storeId, String warehouseId, LocalDateTime calculationAt,
                           String idempotencyKey, String requestSha256,
                           Long actorUserId, LocalDateTime at) {
    }

    public record RunComplete(String tenantId, String generationRunId,
                              int suggestionCount, long expectedVersion,
                              LocalDateTime at) {
    }

    public record SuggestionWrite(String suggestionId, String tenantId, String generationRunId,
                                  String policyVersionId, String policyItemId, Long storeId,
                                  String warehouseId, Long skuId, String skuCode,
                                  Long baseUnitId, Long purchaseUnitId, String supplierId,
                                  BigDecimal onHandQuantity, BigDecimal reservedQuantity,
                                  BigDecimal frozenQuantity, BigDecimal safetyStockQuantity,
                                  BigDecimal availableQuantity, BigDecimal confirmedInTransitQuantity,
                                  BigDecimal effectiveQuantity, BigDecimal minimumBaseQuantity,
                                  BigDecimal maximumBaseQuantity, BigDecimal requiredBaseQuantity,
                                  BigDecimal suggestedPurchaseQuantity,
                                  BigDecimal minimumOrderQuantity, BigDecimal orderMultiple,
                                  long conversionNumerator, long conversionDenominator,
                                  long inputLedgerSequence, long inputBalanceVersion,
                                  String reasonCode, String contentSha256,
                                  Long actorUserId, LocalDateTime at) {
    }

    public record SuggestionStateUpdate(String tenantId, String suggestionId,
                                        String expectedState, String nextState,
                                        long expectedVersion, String purchaseOrderId,
                                        String failureCode, Long reviewerUserId,
                                        Long approverUserId, LocalDateTime at) {
    }

    public record EventWrite(String eventId, String tenantId, Long storeId,
                             String suggestionId, String eventType, String idempotencyKey,
                             String commandSha256, String resultState,
                             String resultReferenceId, Long actorUserId,
                             String correlationId, String payloadJson,
                             String payloadSha256, LocalDateTime at) {
    }

    public record AuditWrite(String auditId, String tenantId, Long storeId,
                             String actionCode, String aggregateType, String aggregateId,
                             Long actorUserId, String commandId, String correlationId,
                             String beforeValue, String afterValue, String requestSha256,
                             String reasonCode, LocalDateTime at) {
    }

    public record OutboxWrite(String eventId, String tenantId, String eventType,
                              String aggregateId, long aggregateVersion,
                              String correlationId, String payloadJson,
                              String payloadSha256, LocalDateTime at) {
    }
}
