package com.jingshanghui.pos.procurement.application.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 补货规则、命令和查询模型；tenant_id 始终由可信上下文注入。 */
public final class ReplenishmentModels {

    private ReplenishmentModels() {
    }

    public record CreatePolicy(String policyVersionId, Long storeId, String warehouseId, int versionNo,
                               Instant effectiveFrom, List<PolicyItemInput> items,
                               String idempotencyKey, String correlationId) {
        public CreatePolicy { items = items == null ? List.of() : List.copyOf(items); }
    }

    /** 规则数量以基础单位表达；采购最小量和倍数以采购单位表达。 */
    public record PolicyItemInput(String policyItemId, Long skuId, Long purchaseUnitId,
                                  String supplierId, BigDecimal minimumBaseQuantity,
                                  BigDecimal maximumBaseQuantity, BigDecimal minimumOrderQuantity,
                                  BigDecimal orderMultiple, boolean includeConfirmedInTransit,
                                  long unitPriceMinor, int taxRateBps) {
    }

    public record PolicyCommand(String policyVersionId, long expectedVersion,
                                String idempotencyKey, String reason, String correlationId) {
    }

    public record GenerateSuggestions(String generationRunId, String policyVersionId,
                                      Instant calculationAt, String idempotencyKey,
                                      String correlationId) {
    }

    public record SuggestionCommand(String suggestionId, long expectedVersion,
                                    String idempotencyKey, String reason, String correlationId) {
    }

    public record CreatePurchaseDraft(String suggestionId, long expectedVersion,
                                      String purchaseOrderId, LocalDate expectedDate,
                                      String idempotencyKey, String correlationId) {
    }

    /** 版本化补货规则头；发布内容不可变。 */
    public record PolicyView(String policyVersionId, Long storeId, String warehouseId,
                             int versionNo, String state, LocalDateTime effectiveFrom,
                             String contentSha256, long version) {
    }

    /** 补货规则明细冻结 Catalog 与 Procurement 的单位、供应商和商业参数。 */
    public record PolicyItemView(String policyItemId, String policyVersionId, Long skuId,
                                 String skuCode, Long baseUnitId, Long purchaseUnitId,
                                 long conversionNumerator, long conversionDenominator,
                                 String supplierId, BigDecimal minimumBaseQuantity,
                                 BigDecimal maximumBaseQuantity, BigDecimal minimumOrderQuantity,
                                 BigDecimal orderMultiple, boolean includeConfirmedInTransit,
                                 long unitPriceMinor, int taxRateBps, String itemSha256) {
    }

    public record PolicyDetail(PolicyView policy, List<PolicyItemView> items) {
        public PolicyDetail { items = List.copyOf(items); }
    }

    /** 一次生成运行是稳定幂等边界；重复运行返回原建议集合。 */
    public record GenerationRunView(String generationRunId, String policyVersionId,
                                    Long storeId, String warehouseId, LocalDateTime calculationAt,
                                    String requestSha256, String state, int suggestionCount,
                                    long version) {
    }

    /** 可解释建议冻结所有输入和计算结果，前端不得重新计算。 */
    public record SuggestionView(String suggestionId, String generationRunId,
                                 String policyVersionId, String policyItemId, Long storeId,
                                 String warehouseId, Long skuId, String skuCode,
                                 Long baseUnitId, Long purchaseUnitId, String supplierId,
                                 BigDecimal onHandQuantity, BigDecimal reservedQuantity,
                                 BigDecimal frozenQuantity, BigDecimal safetyStockQuantity,
                                 BigDecimal availableQuantity, BigDecimal confirmedInTransitQuantity,
                                 BigDecimal effectiveQuantity, BigDecimal minimumBaseQuantity,
                                 BigDecimal maximumBaseQuantity, BigDecimal requiredBaseQuantity,
                                 BigDecimal suggestedPurchaseQuantity, BigDecimal minimumOrderQuantity,
                                 BigDecimal orderMultiple, long conversionNumerator,
                                 long conversionDenominator, long inputLedgerSequence,
                                 long inputBalanceVersion, String reasonCode, String state,
                                 String contentSha256, String purchaseOrderId, String failureCode,
                                 Long reviewerUserId, Long approverUserId, LocalDateTime createdAt,
                                 long version) {
    }

    public record GenerationResult(GenerationRunView run, List<SuggestionView> suggestions,
                                   boolean duplicate) {
        public GenerationResult { suggestions = List.copyOf(suggestions); }
    }

    /** 只追加事件保存稳定命令结果，用于 ACK 丢失或进程重启后的安全恢复。 */
    public record IdempotencyView(String idempotencyKey, String commandSha256,
                                  String aggregateId, String resultState,
                                  String resultReferenceId) {
    }
}
