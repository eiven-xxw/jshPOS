package com.jingshanghui.pos.member.infrastructure.persistence.mapper;

import com.jingshanghui.pos.member.application.model.BenefitViews.EntitlementSnapshotView;
import com.jingshanghui.pos.member.application.model.BenefitViews.PolicyVersionView;
import com.jingshanghui.pos.member.application.port.BenefitPersistencePort.*;
import com.jingshanghui.pos.member.application.port.MemberBenefitPackageSourcePort.BenefitPackageRow;
import com.jingshanghui.pos.member.infrastructure.persistence.BenefitPersistenceParams.ActiveLookup;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** T2-MEM-003 权益持久化 Mapper；复杂 SQL 只能存放于配套 XML。 */
public interface BenefitPersistenceMapper {
    int insertPolicy(PolicyWrite value);
    int insertVersion(VersionWrite value);
    int insertScopes(@Param("values") List<ScopeWrite> values);
    int insertMappings(@Param("values") List<MappingWrite> values);
    PolicyVersionView findVersion(@Param("tenantId") String tenantId, @Param("policyId") String policyId,
                                  @Param("versionId") String versionId);
    PolicyVersionView lockVersion(@Param("tenantId") String tenantId, @Param("policyId") String policyId,
                                  @Param("versionId") String versionId);
    int transitionVersion(VersionTransition value);
    int insertStateEvent(StateEventWrite value);
    PolicyVersionView findActiveVersion(ActiveLookup value);
    int insertSnapshot(SnapshotWrite value);
    EntitlementSnapshotView findSnapshot(@Param("tenantId") String tenantId,
                                         @Param("snapshotId") String snapshotId);
    StoredCommand findCommand(@Param("tenantId") String tenantId, @Param("commandType") String commandType,
                              @Param("idempotencyKey") String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertAudit(AuditWrite value);
    int insertOutbox(OutboxWrite value);
    List<BenefitPackageRow> listForPackage(@Param("tenantId") String tenantId,
                                           @Param("storeId") Long storeId,
                                           @Param("windowStart") LocalDateTime windowStart,
                                           @Param("windowEnd") LocalDateTime windowEnd);
}
