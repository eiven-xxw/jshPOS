package com.jingshanghui.pos.promotion.application.port;

import com.jingshanghui.pos.promotion.application.model.PromotionViews.RuleVersionView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.MemberBenefitPackageView;
import com.jingshanghui.pos.promotion.domain.PromotionModels.RuleVersion;

import java.time.LocalDateTime;
import java.util.List;

/** 复杂促销事实的 XML Mapper 仓储端口。 */
public interface PromotionPersistencePort {
    void insertVersion(VersionWrite value);
    void insertScope(ScopeWrite value);
    void insertBenefit(BenefitWrite value);
    RuleVersionView findVersion(String tenantId, String ruleId, String ruleVersionId);
    int changeState(StateUpdate value);
    List<PublishedRuleRow> listPublishedRules(String tenantId, Long storeId, String channel, LocalDateTime at);
    PublishedRuleRow findRuleDefinition(String tenantId, String ruleVersionId);
    void insertQuote(QuoteWrite value);
    void insertQuoteLine(QuoteLineWrite value);
    void insertAdjustment(AdjustmentWrite value);
    StoredCommand findCommand(String tenantId, String commandType, String idempotencyKey);
    void insertCommand(CommandWrite value);
    StoredQuote findQuoteByKey(String tenantId, Long storeId, String terminalId, String key);
    List<StoredQuoteLine> listQuoteLines(String tenantId, String quoteId);
    void insertAudit(AuditWrite value);
    void insertOutbox(OutboxWrite value);
    List<PublishedRuleRow> listPackageRuleDefinitions(String tenantId, Long storeId, LocalDateTime windowStart,
                                                       LocalDateTime windowEnd);
    PackageView findLatestPackage(String tenantId, Long storeId);
    PackageView findPackage(String tenantId, Long storeId, long packageVersion);
    void insertPackage(PackageWrite value);
    void insertPackageItem(PackageItemWrite value);
    List<PublishedRuleRow> listPackageRules(String tenantId, Long storeId, long packageVersion);
    StoredQuote findQuote(String tenantId, String quoteId);
    /** 在人工优惠、成交冻结等串行事务中锁定报价聚合。 */
    StoredQuote lockQuote(String tenantId, String quoteId);
    List<StoredAdjustment> listQuoteAdjustments(String tenantId, String quoteId);
    ManualPolicyRow findManualPolicy(String tenantId, Long storeId);
    ManualEvent findPendingManualEvent(String tenantId, String quoteId);
    ManualEvent findLatestAppliedManualEvent(String tenantId, String quoteId);
    List<ManualEvent> listAppliedManualEvents(String tenantId, String quoteId);
    ManualEvent findLatestManualEvent(String tenantId, String authorizationId);
    void insertManualEvent(ManualEventWrite value);
    StoredSnapshot findSnapshotByQuote(String tenantId, String quoteId);
    StoredSnapshot findSnapshotByOrder(String tenantId, String orderId);
    StoredSnapshot findSnapshot(String tenantId, String snapshotId);
    StoredSnapshot lockSnapshot(String tenantId, String snapshotId);
    List<StoredSnapshotLine> listSnapshotLines(String tenantId, String snapshotId);
    ExistingRefund findRefund(String tenantId, String refundId);
    List<RefundHistoryRow> listRefundHistory(String tenantId, String snapshotId);
    void insertSnapshot(SnapshotWrite value);
    void insertSnapshotLine(SnapshotLineWrite value);
    void insertRefundAllocation(RefundAllocationWrite value);
    void insertMemberBenefitBinding(MemberBenefitBindingWrite value);
    StoredMemberBenefitBinding findMemberBenefitBinding(String tenantId, String quoteId);
    MemberBenefitPackageView findLatestMemberBenefitPackage(String tenantId, Long storeId);
    MemberBenefitPackageView findMemberBenefitPackage(String tenantId, Long storeId, long packageVersion);
    void insertMemberBenefitPackage(MemberBenefitPackageWrite value);

    /**
     * 写入不可变规则版本。
     * @param tenantId 可信租户 @param ruleVersionId 规则版本ULID @param ruleId 规则ULID
     * @param versionNo 规则版本序号 @param ruleType 白名单规则类型 @param priority 规则优先级
     * @param stackMode 叠加模式 @param exclusiveGroup 互斥组 @param effectiveFrom 生效时间UTC
     * @param effectiveTo 失效时间UTC @param state 生命周期状态 @param contentSha256 规则内容摘要
     * @param engineVersion 兼容引擎版本 @param createdBy 创建人
     */
    record VersionWrite(String tenantId, String ruleVersionId, String ruleId, int versionNo,
                        String ruleType, int priority, String stackMode, String exclusiveGroup,
                        LocalDateTime effectiveFrom, LocalDateTime effectiveTo, String state,
                        String contentSha256, String engineVersion, Long createdBy) { }
    /**
     * 写入规则作用域维度。
     * @param tenantId 可信租户 @param scopeId 作用域ULID @param ruleVersionId 规则版本ULID
     * @param dimensionType 维度类型 @param dimensionValue 维度值
     */
    record ScopeWrite(String tenantId, String scopeId, String ruleVersionId,
                      String dimensionType, String dimensionValue) { }
    /**
     * 写入规则优惠参数。
     * @param tenantId 可信租户 @param benefitId 优惠ULID @param ruleVersionId 规则版本ULID
     * @param amountMinor 优惠金额最小货币单位 @param discountRate 精确折扣率 @param nthValue 第N件序号
     * @param thresholdMinor 满额门槛最小货币单位 @param thresholdQuantity 满件精确数量
     * @param bundlePriceMinor 组合价最小货币单位 @param bundleComponentsJson 规范化组合组件JSON
     */
    record BenefitWrite(String tenantId, String benefitId, String ruleVersionId, Long amountMinor,
                        java.math.BigDecimal discountRate, Integer nthValue, Long thresholdMinor,
                        java.math.BigDecimal thresholdQuantity, Long bundlePriceMinor,
                        String bundleComponentsJson) { }
    /**
     * 按乐观锁推进规则状态。
     * @param tenantId 可信租户 @param ruleId 规则ULID @param ruleVersionId 规则版本ULID
     * @param fromState 原状态 @param toState 目标状态 @param expectedVersion 期望版本
     * @param actorUserId 操作人 @param at 操作时间UTC
     */
    record StateUpdate(String tenantId, String ruleId, String ruleVersionId, String fromState,
                       String toState, int expectedVersion, Long actorUserId, LocalDateTime at) { }
    /**
     * 已发布规则的计算投影。
     * @param ruleVersionId 规则版本ULID @param ruleType 规则类型 @param priority 优先级
     * @param stackMode 叠加模式 @param exclusiveGroup 互斥组 @param effectiveFrom 生效时间UTC
     * @param effectiveTo 失效时间UTC @param scopeTokens 规范化作用域令牌 @param amountMinor 优惠金额
     * @param discountRate 精确折扣率 @param nthValue 第N件序号 @param thresholdMinor 满额门槛
     * @param thresholdQuantity 满件门槛 @param bundlePriceMinor 组合价 @param bundleComponentsJson 组合组件JSON
     */
    record PublishedRuleRow(String ruleVersionId, String ruleType, int priority, String stackMode,
                            String exclusiveGroup, LocalDateTime effectiveFrom, LocalDateTime effectiveTo,
                            String scopeTokens, Long amountMinor, java.math.BigDecimal discountRate,
                            Integer nthValue, Long thresholdMinor, java.math.BigDecimal thresholdQuantity,
                            Long bundlePriceMinor, String bundleComponentsJson) { }
    /**
     * 写入不可变询价事实。
     * @param tenantId 可信租户 @param quoteId 询价ULID @param storeId 门店 @param terminalId 终端
     * @param idempotencyKey 幂等键 @param requestSha256 请求摘要 @param engineVersion 引擎版本
     * @param packageVersion 规则包版本 @param businessTime 业务时间UTC @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额 @param payableAmountMinor 应付金额 @param currency ISO币种
     * @param resultSha256 结果摘要
     */
    record QuoteWrite(String tenantId, String quoteId, Long storeId, String terminalId, String idempotencyKey,
                      String requestSha256, String engineVersion, long packageVersion, LocalDateTime businessTime,
                      long grossAmountMinor, long discountAmountMinor, long payableAmountMinor, String currency,
                      String resultSha256) { }
    /**
     * 写入不可变询价行快照。
     * @param tenantId 可信租户 @param quoteLineId 询价行ULID @param quoteId 询价ULID
     * @param sourceLineId 购物行ULID @param lineNo 稳定行号 @param skuId 商品SKU
     * @param quantity 精确数量 @param unitPriceMinor 单价最小货币单位 @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额 @param payableAmountMinor 应付金额
     */
    record QuoteLineWrite(String tenantId, String quoteLineId, String quoteId, String sourceLineId, int lineNo,
                          Long skuId, java.math.BigDecimal quantity, long unitPriceMinor, long grossAmountMinor,
                          long discountAmountMinor, long payableAmountMinor) { }
    /**
     * 写入优惠调整与解释。
     * @param tenantId 可信租户 @param adjustmentId 调整ULID @param quoteId 询价ULID
     * @param sourceLineId 来源行ULID @param sourceType 来源类型 @param sourceId 来源规则或授权ULID
     * @param calculationStage 计算阶段 @param amountMinor 调整金额最小货币单位
     * @param explanationCode 机器可读解释码 @param appliedFlag 是否实际应用 @param ordinalNo 稳定顺序
     */
    record AdjustmentWrite(String tenantId, String adjustmentId, String quoteId, String sourceLineId,
                           String sourceType, String sourceId, String calculationStage, long amountMinor,
                           String explanationCode, boolean appliedFlag, int ordinalNo) { }
    /**
     * 已存询价幂等投影。
     * @param quoteId 询价ULID @param requestSha256 请求摘要 @param resultSha256 结果摘要
     * @param engineVersion 引擎版本 @param packageVersion 规则包版本 @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额 @param payableAmountMinor 应付金额
     */
    record StoredQuote(String quoteId, Long storeId, String terminalId, LocalDateTime businessTime,
                       String currency, String requestSha256, String resultSha256, String engineVersion,
                       long packageVersion, long grossAmountMinor, long discountAmountMinor,
                       long payableAmountMinor) { }
    /**
     * 已存询价行投影。
     * @param sourceLineId 来源购物行ULID @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额 @param payableAmountMinor 应付金额
     */
    record StoredQuoteLine(String sourceLineId, int lineNo, Long skuId, java.math.BigDecimal quantity,
                           long grossAmountMinor, long discountAmountMinor, long payableAmountMinor) { }
    /** 已存报价调整，用于不依赖当前规则重建不可变报价结果。 */
    record StoredAdjustment(String sourceLineId, String sourceType, String sourceId, String calculationStage,
                            long amountMinor, String explanationCode, boolean appliedFlag, int ordinalNo) { }
    /** Gate 0 配置 Owner 提供的门店优先、租户回退人工优惠策略投影。 */
    record ManualPolicyRow(long policyVersionId, String contentSha256, String contentJson) { }
    /**
     * 人工优惠只追加事件。
     * @param authorizationId 授权 ULID @param eventSequence 事件序号 @param state 当前状态
     * @param quoteId 原报价 @param storeId 门店 @param terminalId 终端 @param actionType 动作
     * @param sourceLineId 目标行 @param amountOrRate 原始值 @param paymentMethod 支付方式
     * @param beforeFingerprint 应用前摘要 @param previewFingerprint 预检结果摘要
     * @param incrementalDiscountMinor 增量优惠 @param policyVersionId 策略版本
     * @param policySha256 策略摘要 @param withoutApprovalMinor 免复核上限
     * @param withApprovalMinor 复核硬上限 @param minimumLinePayableMinor 行底价
     * @param maximumRoundingMinor 抹零上限 @param roundingMultiplesJson 抹零倍数快照
     * @param operatorUserId 操作人 @param approverUserId 复核人 @param businessDate 业务日
     * @param resultJson 规范化结果 @param resultSha256 结果摘要
     */
    record ManualEvent(String authorizationId, int eventSequence, String state, String quoteId, Long storeId,
                       String terminalId, String actionType, String sourceLineId, String amountOrRate,
                       String paymentMethod, String beforeFingerprint, String previewFingerprint,
                       long incrementalDiscountMinor, long policyVersionId, String policySha256,
                       long withoutApprovalMinor, long withApprovalMinor, long minimumLinePayableMinor,
                       long maximumRoundingMinor, String roundingMultiplesJson, Long operatorUserId,
                       Long approverUserId, java.time.LocalDate businessDate, String resultJson,
                       String resultSha256) { }
    /** 人工优惠事件写入模型；原因、命令和关联标识一并冻结。 */
    record ManualEventWrite(String tenantId, String eventId, String authorizationId, int eventSequence,
                            String state, String commandId, String requestSha256, String quoteId, Long storeId,
                            String terminalId, String actionType, String sourceLineId, String amountOrRate,
                            String paymentMethod, String beforeFingerprint, String previewFingerprint,
                            long incrementalDiscountMinor, long policyVersionId, String policySha256,
                            long withoutApprovalMinor, long withApprovalMinor, long minimumLinePayableMinor,
                            long maximumRoundingMinor, String roundingMultiplesJson, String reasonCode,
                            String reasonText, Long operatorUserId, Long approverUserId,
                            java.time.LocalDate businessDate, String correlationId, String resultJson,
                            String resultSha256, LocalDateTime occurredAt) { }
    /** 已冻结成交优惠快照头。 */
    record StoredSnapshot(String snapshotId, String orderId, String quoteId, Long storeId, String terminalId,
                          java.time.LocalDate businessDate, String currency, String quoteFingerprint,
                          String snapshotSha256, long grossAmountMinor, long discountAmountMinor,
                          long payableAmountMinor) { }
    /** 已冻结成交行及来源分摊摘要。 */
    record StoredSnapshotLine(String lineId, int lineNo, Long skuId, java.math.BigDecimal quantity,
                              long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                              String sourceAllocationsJson, String sourceAllocationsSha256) { }
    /** 已存在退款标识，用于阻断同一业务退款标识对应不同命令。 */
    record ExistingRefund(String snapshotId, String requestSha256) { }
    /** 按成交行聚合的累计退款恢复事实。 */
    record RefundHistoryRow(String lineId, java.math.BigDecimal quantity, long grossAmountMinor,
                            long discountAmountMinor, long payableAmountMinor) { }
    /** 成交快照头写入。 */
    record SnapshotWrite(String tenantId, String snapshotId, String orderId, String quoteId, Long storeId,
                         String terminalId, java.time.LocalDate businessDate, String currency,
                         String quoteFingerprint, String snapshotSha256, long grossAmountMinor,
                         long discountAmountMinor, long payableAmountMinor, Long actorUserId,
                         String correlationId, LocalDateTime occurredAt) { }
    /** 成交快照行写入。 */
    record SnapshotLineWrite(String tenantId, String allocationId, String snapshotId, String lineId,
                             int lineNo, Long skuId, java.math.BigDecimal quantity, long grossAmountMinor,
                             long discountAmountMinor, long payableAmountMinor, String sourceAllocationsJson,
                             String sourceAllocationsSha256) { }
    /** 只追加退款分摊流水写入。 */
    record RefundAllocationWrite(String tenantId, String refundAllocationId, String snapshotId,
                                 String refundId, String lineId, String commandId, String requestSha256,
                                 java.math.BigDecimal quantity, long grossAmountMinor, long discountAmountMinor,
                                 long payableAmountMinor, java.math.BigDecimal cumulativeQuantity,
                                 long cumulativeGrossAmountMinor, long cumulativeDiscountAmountMinor,
                                 long cumulativePayableAmountMinor, Long actorUserId, String correlationId,
                                 LocalDateTime occurredAt) { }
    /** 促销 Owner 对本次会员权益路径选择保存的不可变无 PII 事实。 */
    record MemberBenefitBindingWrite(String tenantId, String bindingId, String quoteId,
                                     String entitlementSnapshotId, String benefitVersionId,
                                     String selectedPath, String memberPriceVersionsJson,
                                     long capabilityConfigVersion, String capabilitySha256,
                                     String rightsDigest, String explanationSha256,
                                     String contentSha256, LocalDateTime occurredAt) { }
    /** 已保存会员权益路径，供幂等重放和后续成交冻结使用。 */
    record StoredMemberBenefitBinding(String quoteId, String entitlementSnapshotId,
                                      String benefitVersionId, String selectedPath,
                                      String memberPriceVersionsJson, long capabilityConfigVersion,
                                      String capabilitySha256, String rightsDigest,
                                      String explanationSha256, String contentSha256) { }
    /**
     * 已存命令的精确重放事实。
     * @param requestSha256 命令规范化摘要 @param aggregateId 聚合标识
     * @param resultSha256 原结果摘要 @param resultJson 原结果JSON
     */
    record StoredCommand(String requestSha256, String aggregateId, String resultSha256,
                         String resultJson) { }
    /**
     * 写入命令幂等结果。
     * @param tenantId 可信租户 @param commandResultId 结果ULID @param commandType 命令类型
     * @param idempotencyKey 命令ULID @param requestSha256 命令摘要 @param aggregateType 聚合类型
     * @param aggregateId 聚合标识 @param resultSha256 结果摘要 @param resultJson 脱敏结果JSON
     */
    record CommandWrite(String tenantId, String commandResultId, String commandType, String idempotencyKey,
                        String requestSha256, String aggregateType, String aggregateId,
                        String resultSha256, String resultJson) { }
    /**
     * 写入不可变领域审计事件。
     * @param tenantId 可信租户 @param auditEventId 审计ULID @param actionCode 动作码
     * @param targetType 目标类型 @param targetId 目标ULID @param actorUserId 操作人
     * @param correlationId 关联ULID @param beforeSha256 变更前摘要 @param afterSha256 变更后摘要
     * @param summaryJson 脱敏摘要JSON @param occurredAt 发生时间UTC
     */
    record AuditWrite(String tenantId, String auditEventId, String actionCode, String targetType, String targetId,
                      Long actorUserId, String correlationId, String beforeSha256, String afterSha256,
                      String summaryJson, LocalDateTime occurredAt) { }
    /**
     * 写入促销领域Outbox事件。
     * @param tenantId 可信租户 @param outboxId 事件ULID @param eventType 事件类型
     * @param aggregateType 聚合类型 @param aggregateId 聚合ULID @param aggregateVersion 聚合版本
     * @param payloadJson 版本化载荷JSON @param payloadSha256 载荷摘要 @param availableAt 可投递时间UTC
     */
    record OutboxWrite(String tenantId, String outboxId, String eventType, String aggregateType,
                       String aggregateId, long aggregateVersion, String payloadJson, String payloadSha256,
                       LocalDateTime availableAt) { }
    /**
     * 写入不可变离线规则包元数据。
     * @param tenantId 可信租户 @param packageId 包ULID @param storeId 门店
     * @param packageVersion 单调包版本 @param previousVersion 前一包版本 @param schemaVersion 包Schema版本
     * @param engineVersion 兼容引擎版本 @param payloadSha256 内容摘要 @param signingKeyId 签名密钥版本
     * @param objectKey 租户命名空间对象键 @param recordCount 规则记录数 @param generatedAt 生成时间UTC
     * @param expiresAt 过期时间UTC
     */
    record PackageWrite(String tenantId, String packageId, Long storeId, long packageVersion,
                         long previousVersion, String schemaVersion, String engineVersion, String payloadSha256,
                         String signingKeyId, String objectKey, int recordCount, LocalDateTime generatedAt,
                         LocalDateTime expiresAt) { }
    /**
     * 写入规则包冻结成员。
     * @param tenantId 可信租户 @param packageItemId 条目ULID @param packageId 包ULID
     * @param storeId 绑定门店 @param packageVersion 包版本 @param ordinalNo 稳定序号
     * @param ruleVersionId 冻结规则版本 @param ruleContentSha256 冻结规则AST摘要
     */
    record PackageItemWrite(String tenantId, String packageItemId, String packageId, Long storeId,
                            long packageVersion, int ordinalNo, String ruleVersionId,
                            String ruleContentSha256) { }
    /** 会员权益与会员价离线包只追加元数据。 */
    record MemberBenefitPackageWrite(String tenantId, String packageId, Long storeId,
                                     long packageVersion, long previousVersion, String payloadSha256,
                                     String signingKeyId, String objectKey, int benefitCount,
                                     int memberPriceCount, LocalDateTime generatedAt,
                                     LocalDateTime expiresAt) { }
}
