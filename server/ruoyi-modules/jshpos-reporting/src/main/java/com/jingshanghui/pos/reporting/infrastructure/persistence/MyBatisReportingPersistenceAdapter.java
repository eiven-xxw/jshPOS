package com.jingshanghui.pos.reporting.infrastructure.persistence;

import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.infrastructure.persistence.ReportingPersistenceParams.*;
import com.jingshanghui.pos.reporting.infrastructure.persistence.mapper.ReportingPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

/** MyBatis XML 适配器；复杂聚合、状态条件和显式锁不暴露通用 CRUD。 */
@Repository
@RequiredArgsConstructor
public class MyBatisReportingPersistenceAdapter implements ReportingPersistencePort {
    private final ReportingPersistenceMapper mapper;

    @Override public InboxRow findInbox(String tenantId, String sourceEventId) {
        return mapper.findInbox(new EventKey(tenantId, sourceEventId));
    }
    @Override public boolean insertInboxIfAbsent(String tenantId, SourceEvent event) {
        return mapper.insertInbox(new SourceEventParam(tenantId, event)) == 1;
    }
    @Override public void markInboxApplied(String tenantId, String sourceEventId, Instant appliedAt) {
        if (mapper.markInboxApplied(new EventTime(tenantId, sourceEventId, appliedAt)) != 1) conflict("Inbox 状态更新失败");
    }
    @Override public CheckpointRow lockCheckpoint(String tenantId, String sourceOwner, String partitionKey) {
        return mapper.lockCheckpoint(new CheckpointKey(tenantId, sourceOwner, partitionKey));
    }
    @Override public void insertCheckpoint(String tenantId, String sourceOwner, String partitionKey,
                                           long contiguous, long maximumSeen, String status) {
        if (mapper.insertCheckpoint(new CheckpointInsert(tenantId, sourceOwner, partitionKey, contiguous,
            maximumSeen, status)) != 1) conflict("检查点创建失败");
    }
    @Override public int updateCheckpoint(String tenantId, String sourceOwner, String partitionKey,
                                          long contiguous, long maximumSeen, String status, int expectedVersion) {
        return mapper.updateCheckpoint(new CheckpointUpdate(tenantId, sourceOwner, partitionKey, contiguous,
            maximumSeen, status, expectedVersion));
    }
    @Override public boolean existsAppliedSequence(String tenantId, String sourceOwner, String partitionKey,
                                                   long sequence) {
        return mapper.existsAppliedSequence(new SequenceKey(tenantId, sourceOwner, partitionKey, sequence));
    }
    @Override public void ensureProjectionRegistry(String tenantId, String family, String projectionVersion) {
        mapper.ensureProjectionRegistry(new RegistryParam(tenantId, family, projectionVersion));
    }
    @Override public String activeProjectionVersion(String tenantId, String family) {
        return mapper.activeProjectionVersion(new RegistryParam(tenantId, family, null));
    }
    @Override public void activateProjectionVersion(String tenantId, String family, String projectionVersion) {
        if (mapper.activateProjectionVersion(new RegistryParam(tenantId, family, projectionVersion)) != 1) {
            conflict("活动投影版本切换失败");
        }
    }
    @Override public void upsertSalesProjection(String tenantId, String targetProjectionVersion, SourceEvent event) {
        mapper.upsertSalesProjection(new ProjectionEvent(tenantId, targetProjectionVersion, event, "CURRENT"));
    }
    @Override public void upsertInventoryCostProjection(String tenantId, String targetProjectionVersion,
                                                        SourceEvent event) {
        mapper.upsertInventoryCostProjection(new ProjectionEvent(tenantId, targetProjectionVersion, event, "CURRENT"));
    }
    @Override public void upsertProjectionLineage(String tenantId, String targetProjectionVersion, SourceEvent event,
                                                  CheckpointRow checkpoint, String dimensionSha256,
                                                  Instant processedAt) {
        if (mapper.upsertProjectionLineage(new LineageParam(tenantId, targetProjectionVersion, event, checkpoint,
            dimensionSha256, processedAt)) < 1) conflict("投影血缘写入失败");
    }
    @Override public boolean hasIncompleteCheckpoint(String tenantId, String metricFamily) {
        return mapper.hasIncompleteCheckpoint(new FamilyStatusParam(tenantId, metricFamily, null, null));
    }
    @Override public void updateProjectionStatus(String tenantId, String targetProjectionVersion, SourceEvent event,
                                                 String status) {
        ProjectionEvent param = new ProjectionEvent(tenantId, targetProjectionVersion, event, status);
        if ("SALES".equals(event.metricFamily())) mapper.updateSalesProjectionStatus(param);
        else mapper.updateInventoryProjectionStatus(param);
        mapper.updateLineageProjectionStatus(new FamilyStatusParam(tenantId, event.metricFamily(),
            targetProjectionVersion, status));
    }
    @Override public List<SalesDailyView> querySales(String tenantId, String projectionVersion, LocalDate fromDate,
                                                     LocalDate toDate, Long storeId, String terminalId, Long cashierId) {
        return mapper.querySales(new SalesQueryParam(tenantId, projectionVersion, fromDate, toDate, storeId,
            terminalId, cashierId));
    }
    @Override public List<InventoryCostDailyView> queryInventoryCost(String tenantId, String projectionVersion,
                                                                     LocalDate fromDate, LocalDate toDate,
                                                                     Long storeId, String warehouseId, Long skuId) {
        return mapper.queryInventoryCost(new InventoryQueryParam(tenantId, projectionVersion, fromDate, toDate,
            storeId, warehouseId, skuId));
    }
    @Override public long countSales(String tenantId, String projectionVersion, LocalDate fromDate,
                                     LocalDate toDate, List<Long> storeIds) {
        return mapper.countSales(new CountParam(tenantId, projectionVersion, fromDate, toDate, storeIds));
    }
    @Override public long countInventoryCost(String tenantId, String projectionVersion, LocalDate fromDate,
                                             LocalDate toDate, List<Long> storeIds) {
        return mapper.countInventoryCost(new CountParam(tenantId, projectionVersion, fromDate, toDate, storeIds));
    }
    @Override public List<StoredSourceEvent> listAppliedEvents(String tenantId, LocalDate fromDate, LocalDate toDate) {
        return mapper.listAppliedEvents(new RangeParam(tenantId, null, fromDate, toDate)).stream()
            .map(row -> new StoredSourceEvent(tenantId, toEvent(row))).toList();
    }
    @Override public void clearProjectionVersion(String tenantId, String projectionVersion, LocalDate fromDate,
                                                 LocalDate toDate) {
        RangeParam param = new RangeParam(tenantId, projectionVersion, fromDate, toDate);
        mapper.clearSalesProjection(param);
        mapper.clearInventoryProjection(param);
        mapper.clearProjectionLineage(param);
    }
    @Override public String projectionDigest(String tenantId, String projectionVersion, LocalDate fromDate,
                                             LocalDate toDate) {
        RangeParam param = new RangeParam(tenantId, projectionVersion, fromDate, toDate);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            mapper.listSalesForDigest(param).forEach(row -> update(digest, salesCanonical(row)));
            mapper.listInventoryForDigest(param).forEach(row -> update(digest, inventoryCanonical(row)));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 缺少 SHA-256", exception);
        }
    }
    @Override public boolean insertRebuildIfAbsent(String tenantId, RebuildRow row) {
        return mapper.insertRebuild(new RebuildParam(tenantId, row)) == 1;
    }
    @Override public RebuildRow findRebuild(String tenantId, String rebuildId) {
        return mapper.findRebuild(new ObjectKey(tenantId, rebuildId));
    }
    @Override public void completeRebuild(String tenantId, String rebuildId, String state, long eventCount,
                                          String digest, Instant completedAt) {
        if (mapper.completeRebuild(new RebuildCompletion(tenantId, rebuildId, state, eventCount, digest,
            completedAt)) != 1) conflict("重建状态更新失败");
    }
    @Override public boolean insertExportIfAbsent(String tenantId, ExportRow row) {
        return mapper.insertExport(new ExportParam(tenantId, row)) == 1;
    }
    @Override public ExportRow findExport(String tenantId, String exportId) {
        return mapper.findExport(new ObjectKey(tenantId, exportId));
    }
    @Override public int transitionExport(String tenantId, String exportId, String fromState, String toState,
                                          Long approvedBy, String reasonSha256, int expectedVersion, Instant changedAt) {
        return mapper.transitionExport(new ExportTransition(tenantId, exportId, fromState, toState, approvedBy,
            reasonSha256, expectedVersion, changedAt));
    }
    @Override public void attachArtifact(String tenantId, ArtifactRow row) {
        ArtifactParam param = new ArtifactParam(tenantId, row);
        if (mapper.insertArtifact(param) != 1 || mapper.markExportReady(param) != 1) conflict("制品状态提交失败");
    }
    @Override public ArtifactRow findArtifact(String tenantId, String exportId) {
        return mapper.findArtifact(new ObjectKey(tenantId, exportId));
    }
    @Override public int issueDownloadToken(String tenantId, String exportId, String tokenSha256, Long userId,
                                            Instant tokenExpiresAt, int expectedVersion) {
        return mapper.issueDownloadToken(new TokenIssue(tenantId, exportId, tokenSha256, userId, tokenExpiresAt,
            expectedVersion));
    }
    @Override public int consumeDownloadToken(String tenantId, String exportId, String tokenSha256, Long userId,
                                              Instant consumedAt) {
        return mapper.consumeDownloadToken(new TokenConsume(tenantId, exportId, tokenSha256, userId, consumedAt));
    }
    @Override public List<DifferenceView> listDifferences(String tenantId, int limit) {
        return mapper.listDifferences(new LimitParam(tenantId, limit));
    }
    @Override public DifferenceView findDifference(String tenantId, String differenceId) {
        return mapper.findDifference(new ObjectKey(tenantId, differenceId));
    }
    @Override public void insertDifference(String tenantId, DifferenceRow row) {
        if (mapper.insertDifference(new DifferenceParam(tenantId, row)) != 1) conflict("差异事实写入失败");
    }
    @Override public int transitionDifference(String tenantId, String differenceId, String fromState, String toState,
                                              Long assignedTo, String reasonSha256, int expectedVersion,
                                              Instant changedAt) {
        return mapper.transitionDifference(new DifferenceTransitionParam(tenantId, differenceId, fromState, toState,
            assignedTo, reasonSha256, expectedVersion, changedAt));
    }

    private SourceEvent toEvent(StoredEventRow row) {
        SalesDelta sales = "SALES".equals(row.metricFamily()) ? new SalesDelta(row.orderCount(),
            row.cancelledOrderCount(), row.returnCount(), row.grossMinor(), row.discountMinor(),
            row.surchargeMinor(), row.receivableMinor(), row.refundMinor(), row.cashReceivedMinor(),
            row.cashRefundedMinor(), row.shiftDifferenceMinor(), row.promotionSnapshotCount()) : null;
        InventoryCostDelta inventory = "INVENTORY_COST".equals(row.metricFamily())
            ? new InventoryCostDelta(row.onHandDelta(), row.availableDelta(), row.reservedDelta(),
                row.ledgerQuantityDelta(), row.purchaseQuantityDelta(), row.stocktakeQuantityDelta(),
                row.transferQuantityDelta(), row.inventoryValueDeltaMinor(), row.cogsDeltaMinor(),
                row.purchaseCostDeltaMinor(), row.stocktakeCostDeltaMinor(), row.transferCostDeltaMinor()) : null;
        return new SourceEvent(row.sourceEventId(), row.sourceOwner(), row.sourceAggregateId(), row.sourceSequence(),
            row.partitionKey(), row.schemaVersion(), row.projectionVersion(), row.contentSha256(), row.occurredAt(),
            row.businessDate(), row.orgId(), row.storeId(), blankToNull(row.terminalId()), zeroToNull(row.cashierId()),
            blankToNull(row.warehouseId()), zeroToNull(row.skuId()), row.currency(), row.metricFamily(), sales,
            inventory, row.correlationId());
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private Long zeroToNull(Long value) { return value == null || value == 0 ? null : value; }
    private void update(MessageDigest digest, String value) { digest.update(value.getBytes(StandardCharsets.UTF_8)); }
    private String salesCanonical(SalesDailyView row) {
        return "S|" + row.businessDate() + '|' + row.orgId() + '|' + row.storeId() + '|' + row.terminalId() + '|'
            + row.cashierId() + '|' + row.currency() + '|' + row.orderCount() + '|' + row.cancelledOrderCount() + '|'
            + row.returnCount() + '|' + row.grossMinor() + '|' + row.discountMinor() + '|' + row.surchargeMinor() + '|'
            + row.receivableMinor() + '|' + row.refundMinor() + '|' + row.cashReceivedMinor() + '|'
            + row.cashRefundedMinor() + '|' + row.shiftDifferenceMinor() + '|' + row.promotionSnapshotCount() + '|'
            + row.projectionStatus() + '\n';
    }
    private String inventoryCanonical(InventoryCostDailyView row) {
        return "I|" + row.businessDate() + '|' + row.orgId() + '|' + row.storeId() + '|' + row.warehouseId() + '|'
            + row.skuId() + '|' + row.currency() + '|' + plain(row.onHandDelta()) + '|' + plain(row.availableDelta())
            + '|' + plain(row.reservedDelta()) + '|' + plain(row.ledgerQuantityDelta()) + '|'
            + plain(row.purchaseQuantityDelta()) + '|' + plain(row.stocktakeQuantityDelta()) + '|'
            + plain(row.transferQuantityDelta()) + '|' + plain(row.inventoryValueDeltaMinor()) + '|'
            + plain(row.cogsDeltaMinor()) + '|' + plain(row.purchaseCostDeltaMinor()) + '|'
            + plain(row.stocktakeCostDeltaMinor()) + '|' + plain(row.transferCostDeltaMinor()) + '|'
            + row.projectionStatus() + '\n';
    }
    private String plain(BigDecimal value) { return value == null ? "0.000000" : value.toPlainString(); }
    private void conflict(String message) { throw new ServiceException("RPT-DB-001: " + message, 409); }
}
