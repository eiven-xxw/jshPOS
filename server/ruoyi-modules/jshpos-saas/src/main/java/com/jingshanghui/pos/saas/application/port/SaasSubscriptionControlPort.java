package com.jingshanghui.pos.saas.application.port;

import java.time.LocalDateTime;

/** Subscription Owner 使用的 SaaS 正式边界；实现只写 SaaS 自有事实。 */
public interface SaasSubscriptionControlPort {
    /** @param tenantId 可信目标租户 @param planId 套餐主键 @param entitlementVersionId 已冻结权益版本 */
    record TenantPlanSnapshot(String tenantId, Long planId, String entitlementVersionId, String lifecycleState) { }

    /** 跨 Owner 订阅访问切换命令。 */
    record ApplyAccessCommand(String tenantId, String subscriptionId, String accessMode,
        Integer sourceVersion, String sourceSha256, String idempotencyKey,
        String correlationId, LocalDateTime occurredAt) { }

    TenantPlanSnapshot requireTenantPlan(String tenantId);
    void applySubscriptionAccess(ApplyAccessCommand command);
}
