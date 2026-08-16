package com.jingshanghui.pos.costing.infrastructure.persistence.mapper;

import com.jingshanghui.pos.costing.application.model.CostingViews.BalanceView;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerAggregate;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerView;
import com.jingshanghui.pos.costing.application.model.CostingViews.PolicyView;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.*;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Gate 4C 的 inv_cost_ledger 复杂锁、来源、聚合与写入边界；每个 SQL 显式携带可信 tenant_id。 */
public interface CostingMapper {

    int insertPolicy(PolicyWrite write);

    PolicyView findEffectivePolicy(@Param("tenantId") String tenantId,
                                   @Param("warehouseId") String warehouseId,
                                   @Param("effectiveAt") LocalDateTime effectiveAt);

    int insertBalanceIfAbsent(BalanceSeed seed);

    BalanceView lockBalance(@Param("tenantId") String tenantId,
                            @Param("costDimensionKey") String costDimensionKey);

    BalanceView findBalance(@Param("tenantId") String tenantId,
                            @Param("warehouseId") String warehouseId,
                            @Param("skuId") Long skuId);

    int updateBalance(BalanceUpdate update);

    int rebuildBalance(BalanceUpdate update);

    LedgerView findLedgerByInventory(@Param("tenantId") String tenantId,
                                     @Param("inventoryLedgerId") String inventoryLedgerId);

    LedgerView findLedgerById(@Param("tenantId") String tenantId,
                              @Param("costLedgerId") String costLedgerId);

    LedgerView findSourceLedger(@Param("tenantId") String tenantId,
                                @Param("warehouseId") String warehouseId,
                                @Param("skuId") Long skuId,
                                @Param("sourceType") String sourceType,
                                @Param("sourceLineId") String sourceLineId,
                                @Param("movementType") String movementType);

    int insertLedger(LedgerWrite write);

    List<LedgerView> findLedger(@Param("tenantId") String tenantId,
                                @Param("costDimensionKey") String costDimensionKey,
                                @Param("afterSequence") long afterSequence,
                                @Param("limit") int limit);

    LedgerAggregate aggregateLedger(@Param("tenantId") String tenantId,
                                    @Param("costDimensionKey") String costDimensionKey);

    int insertRebuild(RebuildWrite write);

    int insertAudit(AuditWrite write);

    int insertOutbox(OutboxWrite write);
}
