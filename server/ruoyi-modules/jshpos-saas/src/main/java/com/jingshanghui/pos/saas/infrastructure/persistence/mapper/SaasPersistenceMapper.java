package com.jingshanghui.pos.saas.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.port.SaasPersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** SaaS 复杂状态、只追加事实、幂等与配额 SQL Mapper。 */
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface SaasPersistenceMapper {
    ApplicationRecord findApplication(@Param("applicationId") String applicationId);
    ApplicationRecord findApplicationByCode(@Param("applicationCode") String applicationCode);
    ApplicationRecord lockApplication(@Param("applicationId") String applicationId);
    void insertApplication(ApplicationWrite write);
    int changeApplication(ApplicationChange change);
    void appendApplicationState(StateEventWrite write);
    EntitlementVersionRecord findVersion(@Param("versionId") String versionId);
    EntitlementVersionRecord lockVersion(@Param("versionId") String versionId);
    EntitlementVersionRecord findVersionByPlanNo(@Param("planId") Long planId, @Param("versionNo") Integer versionNo);
    EntitlementVersionRecord findEffectiveVersion(@Param("planId") Long planId, @Param("at") java.time.LocalDateTime at);
    int countOverlappingVersions(@Param("planId") Long planId, @Param("versionId") String versionId,
        @Param("effectiveAt") java.time.LocalDateTime effectiveAt, @Param("expiresAt") java.time.LocalDateTime expiresAt);
    void insertVersion(VersionWrite write);
    void insertItem(ItemWrite write);
    List<EntitlementItemRecord> listItems(@Param("versionId") String versionId);
    int changeVersion(VersionChange change);
    TenantEntitlementRecord findTenantEntitlement(@Param("tenantId") String tenantId);
    void bindTenant(TenantBindingWrite write);
    void seedQuota(TenantQuotaWrite write);
    int changeLifecycle(LifecycleChange change);
    void appendLifecycle(LifecycleEventWrite write);
    void insertCheckpoint(CheckpointWrite write);
    List<String> listCheckpoints(@Param("applicationId") String applicationId);
    CommandRecord findCommand(@Param("scope") String scope, @Param("operation") String operation,
                              @Param("idempotencyKey") String idempotencyKey);
    void insertCommand(CommandWrite write);
    void appendAudit(AuditWrite write);
    void appendOutbox(OutboxWrite write);
    Long quotaUsed(@Param("tenantId") String tenantId, @Param("featureCode") String featureCode);
    int consumeQuota(QuotaWrite write);
}
