package com.jingshanghui.pos.inventory.infrastructure.persistence.mapper;

import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.CommandView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerAggregate;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.PolicyView;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.AnomalyWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.CommandApplied;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.CommandWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.JournalWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.PolicyWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.RebuildUpdate;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Gate 4A 库存持久化边界。
 *
 * <p>复杂锁、聚合和写入 SQL 全部位于 XML，且每个调用显式携带可信 tenant_id。</p>
 */
public interface InventoryMapper {

    int insertPolicy(PolicyWrite write);

    PolicyView findEffectivePolicy(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                   @Param("warehouseId") String warehouseId,
                                   @Param("effectiveAt") LocalDateTime effectiveAt);

    PolicyView findEffectivePolicyByWarehouse(@Param("tenantId") String tenantId,
                                              @Param("warehouseId") String warehouseId,
                                              @Param("effectiveAt") LocalDateTime effectiveAt);

    CommandView findCommand(@Param("tenantId") String tenantId, @Param("eventId") String eventId);

    int insertCommand(CommandWrite write);

    int completeCommand(CommandApplied applied);

    int insertBalanceIfAbsent(BalanceSeed seed);

    BalanceView lockBalance(@Param("tenantId") String tenantId, @Param("dimensionKey") String dimensionKey);

    BalanceView findBalance(@Param("tenantId") String tenantId, @Param("warehouseId") String warehouseId,
                            @Param("skuId") Long skuId);

    int updateBalance(BalanceUpdate update);

    int rebuildBalance(RebuildUpdate update);

    int insertLedger(LedgerWrite write);

    List<LedgerView> findLedger(@Param("tenantId") String tenantId,
                                @Param("dimensionKey") String dimensionKey);

    LedgerAggregate aggregateLedger(@Param("tenantId") String tenantId,
                                    @Param("dimensionKey") String dimensionKey);

    int insertAudit(JournalWrite write);

    int insertOutbox(OutboxWrite write);

    int insertAnomaly(AnomalyWrite write);
}
