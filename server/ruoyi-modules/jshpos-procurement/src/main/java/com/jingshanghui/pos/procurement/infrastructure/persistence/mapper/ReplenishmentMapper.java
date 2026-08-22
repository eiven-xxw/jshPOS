package com.jingshanghui.pos.procurement.infrastructure.persistence.mapper;

import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.GenerationRunView;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.IdempotencyView;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.PolicyItemView;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.PolicyView;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.SuggestionView;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.AuditWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.EventWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.PolicyItemWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.PolicyStateUpdate;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.PolicyWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.RunComplete;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.RunWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.SuggestionStateUpdate;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.SuggestionWrite;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Replenishment Owner Mapper；所有查询必须显式携带可信 tenantId。 */
public interface ReplenishmentMapper {

    int insertPolicy(PolicyWrite write);
    int insertPolicyItem(PolicyItemWrite write);
    PolicyView findPolicy(@Param("tenantId") String tenantId, @Param("policyVersionId") String policyVersionId);
    PolicyView findPolicyByIdempotencyKey(@Param("tenantId") String tenantId,
                                          @Param("idempotencyKey") String idempotencyKey);
    PolicyView lockPolicy(@Param("tenantId") String tenantId, @Param("policyVersionId") String policyVersionId);
    String findPolicyRequestHash(@Param("tenantId") String tenantId,
                                 @Param("policyVersionId") String policyVersionId);
    List<PolicyView> listPolicies(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                  @Param("state") String state, @Param("limit") int limit);
    List<PolicyItemView> findPolicyItems(@Param("tenantId") String tenantId,
                                         @Param("policyVersionId") String policyVersionId);
    int updatePolicyState(PolicyStateUpdate update);

    int insertRun(RunWrite write);
    GenerationRunView findRun(@Param("tenantId") String tenantId,
                              @Param("generationRunId") String generationRunId);
    GenerationRunView findRunByIdempotencyKey(@Param("tenantId") String tenantId,
                                               @Param("idempotencyKey") String idempotencyKey);
    int completeRun(RunComplete update);

    int insertSuggestion(SuggestionWrite write);
    SuggestionView findSuggestion(@Param("tenantId") String tenantId,
                                  @Param("suggestionId") String suggestionId);
    SuggestionView lockSuggestion(@Param("tenantId") String tenantId,
                                  @Param("suggestionId") String suggestionId);
    List<SuggestionView> listSuggestionsByRun(@Param("tenantId") String tenantId,
                                               @Param("generationRunId") String generationRunId);
    List<SuggestionView> listSuggestions(@Param("tenantId") String tenantId,
                                         @Param("storeId") Long storeId,
                                         @Param("state") String state,
                                         @Param("limit") int limit);
    List<SuggestionView> listOpenSuggestionsForUpdate(@Param("tenantId") String tenantId,
                                                       @Param("warehouseId") String warehouseId,
                                                       @Param("skuId") Long skuId);
    int updateSuggestionState(SuggestionStateUpdate update);

    IdempotencyView findIdempotency(@Param("tenantId") String tenantId,
                                    @Param("idempotencyKey") String idempotencyKey);
    IdempotencyView findAuditIdempotency(@Param("tenantId") String tenantId,
                                         @Param("idempotencyKey") String idempotencyKey);
    int insertEvent(EventWrite write);
    int insertAudit(AuditWrite write);
    int insertOutbox(OutboxWrite write);
}
