package com.jingshanghui.pos.saas.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort.QuotaWrite;
import com.jingshanghui.pos.saas.domain.SaasRules;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 服务端套餐权益与原子配额授权；前端展示结果不构成授权。 */
@Service
@RequiredArgsConstructor
public class SaasEntitlementService {
    private final TrustedTenantContext tenantContext;
    private final SaasPersistencePort persistence;
    private final Clock clock;

    @Transactional(readOnly = true)
    public EntitlementDecision decide(String featureCode) {
        String tenantId = tenantContext.requireTenantId(); String feature = SaasRules.code(featureCode, "featureCode");
        TenantEntitlementRecord tenant = persistence.findTenantEntitlement(tenantId);
        if (tenant == null) return new EntitlementDecision(false, "TENANT_NOT_ONBOARDED", null, null, null);
        EntitlementItemRecord item = persistence.listItems(tenant.versionId()).stream().filter(v -> feature.equals(v.featureCode())).findFirst().orElse(null);
        boolean enabled = item != null && Boolean.TRUE.equals(item.enabled());
        SubscriptionAccessRecord access = persistence.findSubscriptionAccess(tenantId);
        boolean subscriptionAllowed = access == null || SaasRules.subscriptionAccessAllowed(access.accessMode(), feature);
        boolean allowed = SaasRules.featureAllowed(tenant.lifecycleState(), enabled, feature) && subscriptionAllowed;
        Long used = item == null || item.quotaLimit() == null ? null : value(persistence.quotaUsed(tenantId, feature));
        String reason = allowed ? (access == null ? "LEGACY_UNMANAGED" : "ALLOWED")
            : (!subscriptionAllowed ? "SUBSCRIPTION_ACCESS_DENIED" : "FEATURE_OR_LIFECYCLE_DENIED");
        return new EntitlementDecision(allowed, reason,
            tenant.versionId(), item == null ? null : item.quotaLimit(), used);
    }

    /** 对配额类权益执行数据库条件更新，越界时失败关闭。 */
    @Transactional
    public EntitlementDecision consume(String featureCode, long delta) {
        if (delta == 0) throw new ServiceException("SAA-QUOTA-001: 配额增量不能为零", 409);
        EntitlementDecision decision = decide(featureCode);
        if (!decision.allowed() || decision.quotaLimit() == null) throw new ServiceException("SAA-QUOTA-002: 权益或配额不可用", 403);
        String tenantId = tenantContext.requireTenantId(); String feature = SaasRules.code(featureCode, "featureCode");
        LocalDateTime at = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (persistence.consumeQuota(new QuotaWrite(tenantId, feature, delta, decision.quotaLimit(), at)) != 1) {
            throw new ServiceException("SAA-QUOTA-003: 配额不足、版本漂移或并发冲突", 409);
        }
        return decide(feature);
    }

    private long value(Long value) { return value == null ? 0L : value; }
}
