package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.VersionView;
import com.jingshanghui.pos.catalog.application.port.MemberPricePersistencePort.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 会员价版本、明细、命令与 Outbox Mapper；SQL 只在 XML。 */
public interface MemberPricePersistenceMapper {
    int insertVersion(VersionWrite value);
    int insertItems(@Param("values") List<ItemWrite> values);
    VersionView findVersion(@Param("tenantId") String tenantId,@Param("versionId") String versionId);
    VersionView lockVersion(@Param("tenantId") String tenantId,@Param("versionId") String versionId);
    int transition(Transition value);
    int countPublishingConflicts(@Param("tenantId") String tenantId,@Param("versionId") String versionId,
                                 @Param("storeId") Long storeId,@Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to);
    MemberPriceCandidate findCandidate(CandidateLookup value);
    StoredCommand findCommand(@Param("tenantId") String tenantId,@Param("commandType") String commandType,
                              @Param("idempotencyKey") String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertOutbox(OutboxWrite value);
}
