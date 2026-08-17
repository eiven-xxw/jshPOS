package com.jingshanghui.pos.reporting.application.port;

import com.jingshanghui.pos.reporting.application.model.ReportingCommands.SourceEvent;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Reporting 持久化端口。来源事实、检查点、投影和状态机必须由实现以显式 tenant 条件访问。
 */
public interface ReportingPersistencePort {
    InboxRow findInbox(String tenantId, String sourceEventId);
    boolean insertInboxIfAbsent(String tenantId, SourceEvent event);
    void markInboxApplied(String tenantId, String sourceEventId, Instant appliedAt);
    CheckpointRow lockCheckpoint(String tenantId, String sourceOwner, String partitionKey);
    void insertCheckpoint(String tenantId, String sourceOwner, String partitionKey, long contiguous,
                          long maximumSeen, String status);
    int updateCheckpoint(String tenantId, String sourceOwner, String partitionKey, long contiguous,
                         long maximumSeen, String status, int expectedVersion);
    boolean existsAppliedSequence(String tenantId, String sourceOwner, String partitionKey, long sequence);
    void ensureProjectionRegistry(String tenantId, String family, String projectionVersion);
    String activeProjectionVersion(String tenantId, String family);
    void activateProjectionVersion(String tenantId, String family, String projectionVersion);
    void upsertSalesProjection(String tenantId, String targetProjectionVersion, SourceEvent event);
    void upsertInventoryCostProjection(String tenantId, String targetProjectionVersion, SourceEvent event);
    void upsertProjectionLineage(String tenantId, String targetProjectionVersion, SourceEvent event,
                                 CheckpointRow checkpoint, String dimensionSha256, Instant processedAt);
    boolean hasIncompleteCheckpoint(String tenantId, String metricFamily);
    void updateProjectionStatus(String tenantId, String targetProjectionVersion, SourceEvent event, String status);
    List<SalesDailyView> querySales(String tenantId, String projectionVersion, LocalDate fromDate,
                                    LocalDate toDate, Long storeId, String terminalId, Long cashierId);
    List<InventoryCostDailyView> queryInventoryCost(String tenantId, String projectionVersion,
                                                    LocalDate fromDate, LocalDate toDate, Long storeId,
                                                    String warehouseId, Long skuId);
    long countSales(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate,
                    List<Long> storeIds);
    long countInventoryCost(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate,
                            List<Long> storeIds);
    List<StoredSourceEvent> listAppliedEvents(String tenantId, LocalDate fromDate, LocalDate toDate);
    void clearProjectionVersion(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate);
    String projectionDigest(String tenantId, String projectionVersion, LocalDate fromDate, LocalDate toDate);
    boolean insertRebuildIfAbsent(String tenantId, RebuildRow row);
    RebuildRow findRebuild(String tenantId, String rebuildId);
    void completeRebuild(String tenantId, String rebuildId, String state, long eventCount,
                         String digest, Instant completedAt);
    boolean insertExportIfAbsent(String tenantId, ExportRow row);
    ExportRow findExport(String tenantId, String exportId);
    int transitionExport(String tenantId, String exportId, String fromState, String toState,
                         Long approvedBy, String reasonSha256, int expectedVersion, Instant changedAt);
    void attachArtifact(String tenantId, ArtifactRow row);
    ArtifactRow findArtifact(String tenantId, String exportId);
    int issueDownloadToken(String tenantId, String exportId, String tokenSha256, Long userId,
                           Instant tokenExpiresAt, int expectedVersion);
    int consumeDownloadToken(String tenantId, String exportId, String tokenSha256, Long userId,
                             Instant consumedAt);
    List<DifferenceView> listDifferences(String tenantId, int limit);
    DifferenceView findDifference(String tenantId, String differenceId);
    void insertDifference(String tenantId, DifferenceRow row);
    int transitionDifference(String tenantId, String differenceId, String fromState, String toState,
                             Long assignedTo, String reasonSha256, int expectedVersion, Instant changedAt);

    /**
     * 来源 Inbox 摘要。
     * @param sourceEventId 来源事件标识
     * @param contentSha256 内容摘要
     * @param status 处理状态
     */
    record InboxRow(String sourceEventId, String contentSha256, String status) {
    }

    /**
     * 来源分区检查点。
     * @param sourceOwner 来源 Owner
     * @param partitionKey 分区键
     * @param contiguousSequence 最大连续序号
     * @param maximumSeenSequence 最大已见序号
     * @param status 投影完整状态
     * @param version 乐观锁版本
     */
    record CheckpointRow(String sourceOwner, String partitionKey, long contiguousSequence,
                         long maximumSeenSequence, String status, int version) {
    }

    /**
     * 可重放来源事件。
     * @param tenantId 可信租户快照
     * @param event 完整已校验来源事件
     */
    record StoredSourceEvent(String tenantId, SourceEvent event) {
    }

    /**
     * 重建状态持久化模型。
     * @param rebuildId 重建标识
     * @param projectionVersion 影子投影版本
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param state 状态
     * @param requestedBy 申请人
     * @param correlationId 关联标识
     * @param eventCount 重放事件数
     * @param projectionDigest 投影摘要
     * @param createdAt 创建时刻
     */
    record RebuildRow(String rebuildId, String projectionVersion, LocalDate fromDate, LocalDate toDate,
                      String state, Long requestedBy, String correlationId, long eventCount,
                      String projectionDigest, Instant createdAt) {
    }

    /**
     * 导出请求持久化模型。
     * @param exportId 导出标识
     * @param requestSha256 请求摘要
     * @param reportType 报表类型
     * @param fromDate 起始业务日
     * @param toDate 结束业务日
     * @param storeIdsCsv 排序后的门店 CSV
     * @param fieldsCsv 排序后的字段 CSV
     * @param state 状态
     * @param approvalRequired 是否要求独立审批
     * @param requestedBy 申请人
     * @param approvedBy 审批人
     * @param estimatedRows 预计行数
     * @param correlationId 关联标识
     * @param artifactSha256 制品摘要
     * @param expiresAt 制品过期时间
     * @param version 乐观锁版本
     * @param createdAt 创建时刻
     */
    record ExportRow(String exportId, String requestSha256, String reportType, LocalDate fromDate,
                     LocalDate toDate, String storeIdsCsv, String fieldsCsv, String state,
                     boolean approvalRequired, Long requestedBy, Long approvedBy, int estimatedRows,
                     String correlationId, String artifactSha256, Instant expiresAt, int version,
                     Instant createdAt) {
    }

    /**
     * 导出制品元数据。
     * @param exportId 导出标识
     * @param objectKey 服务端生成的租户命名空间对象键
     * @param artifactSha256 制品 SHA-256
     * @param sizeBytes 制品字节数
     * @param contentType 媒体类型
     * @param createdAt 创建时刻
     * @param expiresAt 制品过期时刻
     * @param tokenSha256 单次下载令牌摘要
     * @param tokenUserId 令牌绑定用户
     * @param tokenExpiresAt 令牌过期时刻
     * @param downloadedAt 成功下载时刻
     */
    record ArtifactRow(String exportId, String objectKey, String artifactSha256, long sizeBytes,
                       String contentType, Instant createdAt, Instant expiresAt, String tokenSha256,
                       Long tokenUserId, Instant tokenExpiresAt, Instant downloadedAt) {
    }

    /**
     * Reporting 差异持久化模型。
     * @param differenceId 差异 ULID
     * @param differenceType 差异类型
     * @param sourceEventId 来源事件标识
     * @param state 处理状态
     * @param detailSha256 去敏详情摘要
     * @param assignedTo 处理人
     * @param detectedAt 检出时刻
     * @param version 乐观锁版本
     */
    record DifferenceRow(String differenceId, String differenceType, String sourceEventId,
                         String state, String detailSha256, Long assignedTo,
                         Instant detectedAt, int version) {
    }
}
