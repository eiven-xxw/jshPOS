package com.jingshanghui.pos.promotion.application.port;

import com.jingshanghui.pos.promotion.application.model.PromotionViews.RuleVersionView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
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
    record StoredQuote(String quoteId, String requestSha256, String resultSha256, String engineVersion,
                       long packageVersion, long grossAmountMinor, long discountAmountMinor,
                       long payableAmountMinor) { }
    /**
     * 已存询价行投影。
     * @param sourceLineId 来源购物行ULID @param grossAmountMinor 原金额
     * @param discountAmountMinor 优惠金额 @param payableAmountMinor 应付金额
     */
    record StoredQuoteLine(String sourceLineId, long grossAmountMinor, long discountAmountMinor,
                           long payableAmountMinor) { }
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
}
