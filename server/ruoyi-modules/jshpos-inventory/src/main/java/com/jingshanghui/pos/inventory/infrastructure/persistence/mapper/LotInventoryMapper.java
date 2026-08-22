package com.jingshanghui.pos.inventory.infrastructure.persistence.mapper;

import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.AllocationView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.GenericMovementView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LedgerProjection;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotPackageRelease;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.AllocationWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.AuditWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.BalanceRebuild;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.CommandApplied;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.CommandWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.ExpiryProjectionWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.IdentityWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.LotPackageWrite;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 批次库存 XML 持久化边界。
 *
 * <p>所有 SQL 显式携带可信 tenant_id；锁、聚合和 FEFO 查询不得改写成跨 Owner 查询。</p>
 */
public interface LotInventoryMapper {
    CommandView findCommand(@Param("tenantId") String tenantId, @Param("eventId") String eventId);
    int insertCommand(CommandWrite write);
    int completeCommand(CommandApplied applied);
    GenericMovementView findGenericMovement(@Param("tenantId") String tenantId,
                                            @Param("eventId") String eventId,
                                            @Param("sourceLineId") String sourceLineId,
                                            @Param("warehouseId") String warehouseId,
                                            @Param("skuId") Long skuId);
    LotView findLotByIdentityHash(@Param("tenantId") String tenantId,
                                  @Param("warehouseId") String warehouseId,
                                  @Param("skuId") Long skuId,
                                  @Param("contentSha256") String contentSha256);
    LotView findLot(@Param("tenantId") String tenantId, @Param("lotId") String lotId);
    int insertIdentity(IdentityWrite write);
    int insertBalanceIfAbsent(BalanceSeed seed);
    LotView lockLot(@Param("tenantId") String tenantId, @Param("lotId") String lotId);
    List<LotView> lockFefoCandidates(@Param("tenantId") String tenantId,
                                    @Param("warehouseId") String warehouseId,
                                    @Param("skuId") Long skuId,
                                    @Param("businessDate") LocalDate businessDate,
                                    @Param("limit") int limit);
    int updateBalance(BalanceUpdate update);
    int insertLedger(LedgerWrite write);
    int insertAllocation(AllocationWrite write);
    List<AllocationView> findAllocationsByEvent(@Param("tenantId") String tenantId,
                                                @Param("eventId") String eventId);
    List<AllocationView> lockReturnableAllocations(@Param("tenantId") String tenantId,
                                                   @Param("originalSourceId") String originalSourceId,
                                                   @Param("originalSourceLineId") String originalSourceLineId,
                                                   @Param("skuId") Long skuId);
    AllocationView lockTransferableAllocation(@Param("tenantId") String tenantId,
                                               @Param("dispatchId") String dispatchId,
                                               @Param("dispatchLineId") String dispatchLineId,
                                               @Param("sourceLotId") String sourceLotId,
                                               @Param("skuId") Long skuId);
    int upsertExpiryProjection(ExpiryProjectionWrite write);
    int insertAudit(AuditWrite write);
    int insertOutbox(OutboxWrite write);
    List<LotView> findNearExpiry(@Param("tenantId") String tenantId,
                                 @Param("storeId") Long storeId,
                                 @Param("warehouseId") String warehouseId,
                                 @Param("asOfBusinessDate") LocalDate asOfBusinessDate,
                                 @Param("limit") int limit);
    List<LotView> findPackageLots(@Param("tenantId") String tenantId,
                                  @Param("storeId") Long storeId,
                                  @Param("warehouseId") String warehouseId,
                                  @Param("limit") int limit);
    List<LotView> findLots(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                           @Param("warehouseId") String warehouseId, @Param("skuId") Long skuId,
                           @Param("limit") int limit);
    long countLotFacts(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                       @Param("skuId") Long skuId);

    long countStoreLotFacts(@Param("tenantId") String tenantId, @Param("storeId") Long storeId);
    LedgerProjection aggregateLedger(@Param("tenantId") String tenantId, @Param("lotId") String lotId);
    int rebuildBalance(BalanceRebuild rebuild);
    LotPackageRelease lockLatestPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                        @Param("warehouseId") String warehouseId);
    LotPackageRelease findLatestPackage(@Param("tenantId") String tenantId, @Param("storeId") Long storeId,
                                        @Param("warehouseId") String warehouseId);
    LotPackageRelease findPackageByRelease(@Param("tenantId") String tenantId,
                                           @Param("releaseId") String releaseId);
    int insertPackageRelease(LotPackageWrite write);
}
