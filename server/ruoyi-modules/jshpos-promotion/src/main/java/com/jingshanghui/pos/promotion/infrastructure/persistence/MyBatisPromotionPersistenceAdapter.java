package com.jingshanghui.pos.promotion.infrastructure.persistence;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.PublishedConfigReadPort;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.RuleVersionView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.infrastructure.persistence.mapper.PromotionPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/** XML Mapper 复杂事实仓储适配器。 */
@Repository
@RequiredArgsConstructor
public class MyBatisPromotionPersistenceAdapter implements PromotionPersistencePort {
    private static final String MANUAL_POLICY = "PROMOTION_MANUAL_AUTHORITY";
    private final PromotionPersistenceMapper mapper;
    private final PublishedConfigReadPort configs;
    private final TrustedTenantContext tenantContext;
    @Override public void insertVersion(VersionWrite value) { requireOne(mapper.insertVersion(value)); }
    @Override public void insertScope(ScopeWrite value) { requireOne(mapper.insertScope(value)); }
    @Override public void insertBenefit(BenefitWrite value) { requireOne(mapper.insertBenefit(value)); }
    @Override public RuleVersionView findVersion(String tenantId, String ruleId, String ruleVersionId) {
        return mapper.findVersion(tenantId, ruleId, ruleVersionId);
    }
    @Override public int changeState(StateUpdate value) { return mapper.changeState(value); }
    @Override public List<PublishedRuleRow> listPublishedRules(String tenantId, Long storeId, String channel,
                                                               LocalDateTime at) {
        return mapper.listPublishedRules(tenantId, storeId, channel, at);
    }
    @Override public PublishedRuleRow findRuleDefinition(String tenantId, String ruleVersionId) {
        return mapper.findRuleDefinition(tenantId, ruleVersionId);
    }
    @Override public void insertQuote(QuoteWrite value) { requireOne(mapper.insertQuote(value)); }
    @Override public void insertQuoteLine(QuoteLineWrite value) { requireOne(mapper.insertQuoteLine(value)); }
    @Override public void insertAdjustment(AdjustmentWrite value) { requireOne(mapper.insertAdjustment(value)); }
    @Override public StoredCommand findCommand(String tenantId, String commandType, String idempotencyKey) {
        return mapper.findCommand(tenantId, commandType, idempotencyKey);
    }
    @Override public void insertCommand(CommandWrite value) { requireOne(mapper.insertCommand(value)); }
    @Override public StoredQuote findQuoteByKey(String tenantId, Long storeId, String terminalId, String key) {
        return mapper.findQuoteByKey(tenantId, storeId, terminalId, key);
    }
    @Override public List<StoredQuoteLine> listQuoteLines(String tenantId, String quoteId) {
        return mapper.listQuoteLines(tenantId, quoteId);
    }
    @Override public void insertAudit(AuditWrite value) { requireOne(mapper.insertAudit(value)); }
    @Override public void insertOutbox(OutboxWrite value) { requireOne(mapper.insertOutbox(value)); }
    @Override public List<PublishedRuleRow> listPackageRuleDefinitions(String tenantId, Long storeId,
                                                                       LocalDateTime windowStart,
                                                                       LocalDateTime windowEnd) {
        return mapper.listPackageRuleDefinitions(tenantId, storeId, windowStart, windowEnd);
    }
    @Override public PackageView findLatestPackage(String tenantId, Long storeId) {
        return mapper.findLatestPackage(tenantId, storeId);
    }
    @Override public PackageView findPackage(String tenantId, Long storeId, long packageVersion) {
        return mapper.findPackage(tenantId, storeId, packageVersion);
    }
    @Override public void insertPackage(PackageWrite value) { requireOne(mapper.insertPackage(value)); }
    @Override public void insertPackageItem(PackageItemWrite value) { requireOne(mapper.insertPackageItem(value)); }
    @Override public List<PublishedRuleRow> listPackageRules(String tenantId, Long storeId, long packageVersion) {
        return mapper.listPackageRules(tenantId, storeId, packageVersion);
    }
    @Override public StoredQuote findQuote(String tenantId, String quoteId) {
        return mapper.findQuote(tenantId, quoteId);
    }
    @Override public StoredQuote lockQuote(String tenantId, String quoteId) {
        return mapper.lockQuote(tenantId, quoteId);
    }
    @Override public List<StoredAdjustment> listQuoteAdjustments(String tenantId, String quoteId) {
        return mapper.listQuoteAdjustments(tenantId, quoteId);
    }
    @Override public ManualPolicyRow findManualPolicy(String tenantId, Long storeId) {
        if (!tenantContext.requireTenantId().equals(tenantId)) {
            throw new ServiceException("PRM-TEN-002: 促销策略租户上下文不一致", 403);
        }
        return configs.find(MANUAL_POLICY, storeId)
            .map(value -> new ManualPolicyRow(value.configVersionId(), value.contentSha256(), value.contentJson()))
            .orElse(null);
    }
    @Override public ManualEvent findPendingManualEvent(String tenantId, String quoteId) {
        return mapper.findPendingManualEvent(tenantId, quoteId);
    }
    @Override public ManualEvent findLatestAppliedManualEvent(String tenantId, String quoteId) {
        return mapper.findLatestAppliedManualEvent(tenantId, quoteId);
    }
    @Override public List<ManualEvent> listAppliedManualEvents(String tenantId, String quoteId) {
        return mapper.listAppliedManualEvents(tenantId, quoteId);
    }
    @Override public ManualEvent findLatestManualEvent(String tenantId, String authorizationId) {
        return mapper.findLatestManualEvent(tenantId, authorizationId);
    }
    @Override public void insertManualEvent(ManualEventWrite value) { requireOne(mapper.insertManualEvent(value)); }
    @Override public StoredSnapshot findSnapshotByQuote(String tenantId, String quoteId) {
        return mapper.findSnapshotByQuote(tenantId, quoteId);
    }
    @Override public StoredSnapshot findSnapshotByOrder(String tenantId, String orderId) {
        return mapper.findSnapshotByOrder(tenantId, orderId);
    }
    @Override public StoredSnapshot findSnapshot(String tenantId, String snapshotId) {
        return mapper.findSnapshot(tenantId, snapshotId);
    }
    @Override public StoredSnapshot lockSnapshot(String tenantId, String snapshotId) {
        return mapper.lockSnapshot(tenantId, snapshotId);
    }
    @Override public List<StoredSnapshotLine> listSnapshotLines(String tenantId, String snapshotId) {
        return mapper.listSnapshotLines(tenantId, snapshotId);
    }
    @Override public ExistingRefund findRefund(String tenantId, String refundId) {
        return mapper.findRefund(tenantId, refundId);
    }
    @Override public List<RefundHistoryRow> listRefundHistory(String tenantId, String snapshotId) {
        return mapper.listRefundHistory(tenantId, snapshotId);
    }
    @Override public void insertSnapshot(SnapshotWrite value) { requireOne(mapper.insertSnapshot(value)); }
    @Override public void insertSnapshotLine(SnapshotLineWrite value) { requireOne(mapper.insertSnapshotLine(value)); }
    @Override public void insertRefundAllocation(RefundAllocationWrite value) {
        requireOne(mapper.insertRefundAllocation(value));
    }
    @Override public void insertMemberBenefitBinding(MemberBenefitBindingWrite value) {
        requireOne(mapper.insertMemberBenefitBinding(value));
    }
    @Override public StoredMemberBenefitBinding findMemberBenefitBinding(String tenantId, String quoteId) {
        return mapper.findMemberBenefitBinding(tenantId, quoteId);
    }
    @Override public com.jingshanghui.pos.promotion.application.model.PromotionViews.MemberBenefitPackageView
        findLatestMemberBenefitPackage(String tenantId, Long storeId) {
        return mapper.findLatestMemberBenefitPackage(tenantId, storeId);
    }
    @Override public com.jingshanghui.pos.promotion.application.model.PromotionViews.MemberBenefitPackageView
        findMemberBenefitPackage(String tenantId, Long storeId, long packageVersion) {
        return mapper.findMemberBenefitPackage(tenantId, storeId, packageVersion);
    }
    @Override public void insertMemberBenefitPackage(MemberBenefitPackageWrite value) {
        requireOne(mapper.insertMemberBenefitPackage(value));
    }
    private void requireOne(int count) {
        if (count != 1) throw new ServiceException("PRM-STORE-002: 促销事实写入失败", 409);
    }
}
