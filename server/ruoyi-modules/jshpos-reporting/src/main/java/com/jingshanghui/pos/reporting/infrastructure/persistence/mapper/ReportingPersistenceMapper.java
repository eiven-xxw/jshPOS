package com.jingshanghui.pos.reporting.infrastructure.persistence.mapper;

import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort.*;
import com.jingshanghui.pos.reporting.infrastructure.persistence.ReportingPersistenceParams.*;

import java.util.List;

/** Reporting XML_ONLY/READ_PROJECTION Mapper；所有 SQL 必须显式 tenant 条件和字段列表。 */
public interface ReportingPersistenceMapper {
    InboxRow findInbox(EventKey key);
    int insertInbox(SourceEventParam param);
    int markInboxApplied(EventTime param);
    CheckpointRow lockCheckpoint(CheckpointKey key);
    int insertCheckpoint(CheckpointInsert param);
    int updateCheckpoint(CheckpointUpdate param);
    boolean existsAppliedSequence(SequenceKey key);
    int ensureProjectionRegistry(RegistryParam param);
    String activeProjectionVersion(RegistryParam param);
    int activateProjectionVersion(RegistryParam param);
    int upsertSalesProjection(ProjectionEvent param);
    int upsertInventoryCostProjection(ProjectionEvent param);
    int upsertProjectionLineage(LineageParam param);
    boolean hasIncompleteCheckpoint(FamilyStatusParam param);
    int updateSalesProjectionStatus(ProjectionEvent param);
    int updateInventoryProjectionStatus(ProjectionEvent param);
    int updateLineageProjectionStatus(FamilyStatusParam param);
    List<SalesDailyView> querySales(SalesQueryParam param);
    List<SalesDailyView> querySalesPage(SalesPageParam param);
    List<InventoryCostDailyView> queryInventoryCost(InventoryQueryParam param);
    long countSales(CountParam param);
    long countInventoryCost(CountParam param);
    List<StoredEventRow> listAppliedEvents(RangeParam param);
    int clearSalesProjection(RangeParam param);
    int clearInventoryProjection(RangeParam param);
    int clearProjectionLineage(RangeParam param);
    List<SalesDailyView> listSalesForDigest(RangeParam param);
    List<InventoryCostDailyView> listInventoryForDigest(RangeParam param);
    int insertRebuild(RebuildParam param);
    RebuildRow findRebuild(ObjectKey key);
    int completeRebuild(RebuildCompletion param);
    int insertExport(ExportParam param);
    ExportRow findExport(ObjectKey key);
    int transitionExport(ExportTransition param);
    int insertArtifact(ArtifactParam param);
    int markExportReady(ArtifactParam param);
    ArtifactRow findArtifact(ObjectKey key);
    int issueDownloadToken(TokenIssue param);
    int consumeDownloadToken(TokenConsume param);
    List<DifferenceView> listDifferences(LimitParam param);
    DifferenceView findDifference(ObjectKey key);
    int insertDifference(DifferenceParam param);
    int transitionDifference(DifferenceTransitionParam param);
}
