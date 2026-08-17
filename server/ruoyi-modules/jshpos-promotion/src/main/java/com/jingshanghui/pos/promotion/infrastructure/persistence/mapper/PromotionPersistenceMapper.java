package com.jingshanghui.pos.promotion.infrastructure.persistence.mapper;

import com.jingshanghui.pos.promotion.application.model.PromotionViews.RuleVersionView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 复杂促销事实 Mapper；SQL 只能存在于配套 XML 中。 */
public interface PromotionPersistenceMapper {
    int insertVersion(VersionWrite value);
    int insertScope(ScopeWrite value);
    int insertBenefit(BenefitWrite value);
    RuleVersionView findVersion(@Param("tenantId") String tenantId, @Param("ruleId") String ruleId,
                                @Param("ruleVersionId") String ruleVersionId);
    int changeState(StateUpdate value);
    List<PublishedRuleRow> listPublishedRules(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                              @Param("channel") String channel, @Param("at") LocalDateTime at);
    PublishedRuleRow findRuleDefinition(@Param("tenantId") String tenantId,
                                        @Param("ruleVersionId") String ruleVersionId);
    int insertQuote(QuoteWrite value);
    int insertQuoteLine(QuoteLineWrite value);
    int insertAdjustment(AdjustmentWrite value);
    StoredCommand findCommand(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                              @Param("idempotencyKey") String idempotencyKey);
    int insertCommand(CommandWrite value);
    StoredQuote findQuoteByKey(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                               @Param("terminalId") String terminalId, @Param("key") String key);
    List<StoredQuoteLine> listQuoteLines(@Param("tenantId") String tenantId, @Param("quoteId") String quoteId);
    int insertAudit(AuditWrite value);
    int insertOutbox(OutboxWrite value);
    List<PublishedRuleRow> listPackageRuleDefinitions(@Param("tenantId") String tenantId,
                                                       @Param("storeId") Long storeId,
                                                       @Param("windowStart") LocalDateTime windowStart,
                                                       @Param("windowEnd") LocalDateTime windowEnd);
    PackageView findLatestPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
    PackageView findPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                            @Param("packageVersion") long packageVersion);
    int insertPackage(PackageWrite value);
    int insertPackageItem(PackageItemWrite value);
    List<PublishedRuleRow> listPackageRules(@Param("tenantId") String tenantId,
                                            @Param("storeId") Long storeId,
                                            @Param("packageVersion") long packageVersion);
    StoredQuote findQuote(@Param("tenantId") String tenantId, @Param("quoteId") String quoteId);
    StoredQuote lockQuote(@Param("tenantId") String tenantId, @Param("quoteId") String quoteId);
    List<StoredAdjustment> listQuoteAdjustments(@Param("tenantId") String tenantId,
                                                 @Param("quoteId") String quoteId);
    ManualPolicyRow findManualPolicy(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
    ManualEvent findPendingManualEvent(@Param("tenantId") String tenantId, @Param("quoteId") String quoteId);
    ManualEvent findLatestAppliedManualEvent(@Param("tenantId") String tenantId,
                                              @Param("quoteId") String quoteId);
    List<ManualEvent> listAppliedManualEvents(@Param("tenantId") String tenantId,
                                               @Param("quoteId") String quoteId);
    ManualEvent findLatestManualEvent(@Param("tenantId") String tenantId,
                                       @Param("authorizationId") String authorizationId);
    int insertManualEvent(ManualEventWrite value);
}
