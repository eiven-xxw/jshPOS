package com.jingshanghui.pos.reporting.application.model;

import java.time.Instant;
import java.time.LocalDate;

/** T2-RPT-002 Provider 无关对账命令；所有模型均不携带 tenantId 或支付敏感数据。 */
public final class PaymentReconciliationCommands {
    private PaymentReconciliationCommands() {
    }

    /**
     * Payment/Refund Owner 冻结的 Provider 无关事实。
     * @param sourceEventId 来源事件 ULID
     * @param sourceOwner PAYMENT 或 REFUND
     * @param sourceSequence 来源分区单调序号
     * @param partitionKey 来源分区键
     * @param schemaVersion Schema 版本
     * @param contentSha256 规范内容摘要
     * @param occurredAt 事实发生时间
     * @param businessDate Owner 冻结业务日
     * @param orgId 组织标识
     * @param storeId 门店标识
     * @param terminalId 终端标识
     * @param factType PAYMENT 或 REFUND
     * @param reconciliationKey 支付尝试或退款 ULID
     * @param orderId 原订单 ULID
     * @param amountMinor 最小货币单位金额
     * @param currency 币种
     * @param lifecycleStatus SUCCEEDED、FAILED 或 UNKNOWN
     * @param correlationId 关联 ULID
     */
    public record PaymentFact(String sourceEventId, String sourceOwner, long sourceSequence,
                              String partitionKey, String schemaVersion, String contentSha256,
                              Instant occurredAt, LocalDate businessDate, Long orgId, Long storeId,
                              String terminalId, String factType, String reconciliationKey,
                              String orderId, long amountMinor, String currency,
                              String lifecycleStatus, String correlationId) {
    }

    /**
     * 内部合成账单条目；sourceType 与 synthetic 必须保持显式，不能冒充渠道账单。
     * @param billEntryId 合成账单条目 ULID
     * @param batchId 合成批次 ULID
     * @param sourceType 固定 INTERNAL_SYNTHETIC
     * @param synthetic 固定 true
     * @param schemaVersion Schema 版本
     * @param contentSha256 规范内容摘要
     * @param businessDate 合成账单业务日
     * @param orgId 组织标识
     * @param storeId 门店标识
     * @param terminalId 终端标识
     * @param factType PAYMENT 或 REFUND
     * @param reconciliationKey 匹配 ULID
     * @param amountMinor 最小货币单位金额
     * @param currency 币种
     * @param lifecycleStatus SUCCEEDED、FAILED 或 UNKNOWN
     * @param correlationId 关联 ULID
     */
    public record SyntheticBillEntry(String billEntryId, String batchId, String sourceType,
                                     boolean synthetic, String schemaVersion, String contentSha256,
                                     LocalDate businessDate, Long orgId, Long storeId, String terminalId,
                                     String factType, String reconciliationKey, long amountMinor,
                                     String currency, String lifecycleStatus, String correlationId) {
    }

    /** 支付退款对账查询。 */
    public record Query(LocalDate fromDate, LocalDate toDate, Long storeId,
                        String differenceType, String handlingState) {
    }

    /** 差异处理状态迁移。 */
    public record Transition(String reconciliationId, String toState, String reason,
                             int expectedVersion, String correlationId) {
    }

    /** 全量重建命令。 */
    public record Rebuild(String rebuildId, LocalDate fromDate, LocalDate toDate, String correlationId) {
    }
}
