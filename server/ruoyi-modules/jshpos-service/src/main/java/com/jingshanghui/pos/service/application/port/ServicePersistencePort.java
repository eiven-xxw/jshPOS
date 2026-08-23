package com.jingshanghui.pos.service.application.port;

import com.jingshanghui.pos.service.application.model.ServiceModels.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Service Owner 唯一持久化端口；状态、租约和清理只开放具名条件更新，历史/审计/Outbox 只追加。
 */
public interface ServicePersistencePort {
    void insertCatalog(CatalogWrite value);
    void insertCatalogItem(CatalogItemWrite value);
    CatalogRecord findCatalog(String tenantId, String catalogId);
    CatalogRecord lockCatalog(String tenantId, String catalogId);
    List<CatalogItemRecord> listCatalogItems(String tenantId, String catalogId);
    int publishCatalog(CatalogPublish value);

    void insertProject(ProjectWrite value);
    void insertProjectCheck(ProjectCheckWrite value);
    ProjectRecord findProject(String tenantId, String projectId);
    ProjectRecord lockProject(String tenantId, String projectId);
    List<ProjectRecord> listProjects(String tenantId, Long storeId, int limit);
    List<CheckRecord> listProjectChecks(String tenantId, String projectId);
    int changeProjectState(ProjectStateChange value);
    int completeProjectCheck(ProjectCheckComplete value);
    int countMandatoryIncomplete(String tenantId, String projectId);

    void insertTicket(TicketWrite value);
    TicketRecord findTicket(String tenantId, String ticketId);
    TicketRecord lockTicket(String tenantId, String ticketId);
    List<TicketRecord> listTickets(String tenantId, Long storeId, String state, int limit);
    int changeTicket(TicketChange value);

    void appendHistory(HistoryWrite value);
    void insertAttachment(AttachmentWrite value);
    AttachmentStoredRecord findAttachment(String tenantId, String ticketId, String attachmentId);
    List<AttachmentRecord> listAttachments(String tenantId, String ticketId);
    int countAttachments(String tenantId, String ticketId);
    int changeAttachmentState(AttachmentStateChange value);

    CommandRecord findCommand(String tenantId, String operation, String idempotencyKey);
    void appendCommand(CommandWrite value);
    void appendAudit(AuditWrite value);
    void appendOutbox(OutboxWrite value);

    /** @param catalogId 目录ID @param tenantId 可信租户 @param catalogCode 目录编码 @param versionNo 版本号 @param industryTemplate 行业模板 @param name 名称 @param contentSha256 内容摘要 @param creatorUserId 创建人 @param createdAt 创建时间 */
    record CatalogWrite(String catalogId, String tenantId, String catalogCode, Integer versionNo,
                        String industryTemplate, String name, String contentSha256, Long creatorUserId,
                        LocalDateTime createdAt) { }
    /** @param itemId 检查项ID @param tenantId 可信租户 @param catalogId 目录ID @param itemCode 检查项编码 @param itemName 检查项名称 @param mandatory 是否必选 @param sequenceNo 显示顺序 @param createdAt 创建时间 */
    record CatalogItemWrite(String itemId, String tenantId, String catalogId, String itemCode,
                            String itemName, Boolean mandatory, Integer sequenceNo, LocalDateTime createdAt) { }
    /** @param tenantId 可信租户 @param catalogId 目录ID @param expectedVersion 预期版本 @param publisherUserId 发布人 @param publishedAt 发布时间 */
    record CatalogPublish(String tenantId, String catalogId, Integer expectedVersion, Long publisherUserId,
                          LocalDateTime publishedAt) { }
    /** @param projectId 项目ID @param tenantId 可信租户 @param storeId 门店 @param catalogId 已发布目录 @param state 状态 @param ownerUserId 负责人 @param targetDate 目标日期 @param contentSha256 内容摘要 @param createdAt 创建时间 */
    record ProjectWrite(String projectId, String tenantId, Long storeId, String catalogId, String state,
                        Long ownerUserId, LocalDate targetDate, String contentSha256, LocalDateTime createdAt) { }
    /** @param checkId 检查项ID @param tenantId 可信租户 @param projectId 项目ID @param sourceItemId 来源目录项 @param itemCode 编码 @param itemName 名称 @param mandatory 是否必选 @param sequenceNo 显示顺序 @param createdAt 创建时间 */
    record ProjectCheckWrite(String checkId, String tenantId, String projectId, String sourceItemId,
                             String itemCode, String itemName, Boolean mandatory, Integer sequenceNo,
                             LocalDateTime createdAt) { }
    /** @param tenantId 可信租户 @param projectId 项目ID @param fromState 原状态 @param toState 目标状态 @param expectedVersion 预期版本 @param contentSha256 命令摘要 @param updatedAt 更新时间 */
    record ProjectStateChange(String tenantId, String projectId, String fromState, String toState,
                              Integer expectedVersion, String contentSha256, LocalDateTime updatedAt) { }
    /** @param tenantId 可信租户 @param projectId 项目ID @param checkId 检查项ID @param expectedVersion 预期版本 @param completedBy 完成人 @param completionNote 完成说明 @param completedAt 完成时间 */
    record ProjectCheckComplete(String tenantId, String projectId, String checkId, Integer expectedVersion,
                                Long completedBy, String completionNote, LocalDateTime completedAt) { }
    /** @param ticketId 工单ID @param tenantId 可信租户 @param storeId 门店 @param projectId 可选项目 @param serviceType 服务类型 @param priority 优先级 @param subject 主题 @param description 描述 @param state 状态 @param targetAt 内部目标时间 @param contentSha256 内容摘要 @param creatorUserId 创建人 @param createdAt 创建时间 */
    record TicketWrite(String ticketId, String tenantId, Long storeId, String projectId, String serviceType,
                       String priority, String subject, String description, String state, LocalDateTime targetAt,
                       String contentSha256, Long creatorUserId, LocalDateTime createdAt) { }
    /** @param tenantId 可信租户 @param ticketId 工单ID @param fromState 原状态 @param toState 目标状态 @param expectedVersion 预期版本 @param assigneeUserId 责任人 @param leaseUntil 租约截止 @param resolvedBy 解决人 @param closedBy 独立关闭人 @param resolutionSummary 解决摘要 @param contentSha256 命令摘要 @param updatedAt 更新时间 */
    record TicketChange(String tenantId, String ticketId, String fromState, String toState, Integer expectedVersion,
                        Long assigneeUserId, LocalDateTime leaseUntil, Long resolvedBy, Long closedBy,
                        String resolutionSummary, String contentSha256, LocalDateTime updatedAt) { }
    /** @param historyId 历史ID @param tenantId 可信租户 @param storeId 门店 @param aggregateType 聚合类型 @param aggregateId 聚合ID @param actionCode 动作 @param fromState 原状态 @param toState 目标状态 @param fromUserId 原责任人 @param toUserId 新责任人 @param note 说明 @param requestSha256 请求摘要 @param correlationId 关联标识 @param actorUserId 操作人 @param occurredAt 发生时间 */
    record HistoryWrite(String historyId, String tenantId, Long storeId, String aggregateType, String aggregateId,
                        String actionCode, String fromState, String toState, Long fromUserId, Long toUserId,
                        String note, String requestSha256, String correlationId, Long actorUserId,
                        LocalDateTime occurredAt) { }
    /** @param attachmentId 附件ID @param tenantId 可信租户 @param storeId 门店 @param ticketId 工单ID @param objectKey 受控对象键 @param fileName 安全文件名 @param mediaType 媒体类型 @param sizeBytes 字节数 @param sha256 内容摘要 @param state 清理状态 @param uploaderUserId 上传人 @param retentionUntil 保留截止 @param createdAt 创建时间 */
    record AttachmentWrite(String attachmentId, String tenantId, Long storeId, String ticketId, String objectKey,
                           String fileName, String mediaType, Long sizeBytes, String sha256, String state,
                           Long uploaderUserId, LocalDateTime retentionUntil, LocalDateTime createdAt) { }
    /** @param attachmentId 附件ID @param tenantId 可信租户 @param storeId 门店 @param ticketId 工单ID @param objectKey 内部对象键 @param fileName 安全文件名 @param mediaType 媒体类型 @param sizeBytes 字节数 @param sha256 内容摘要 @param state 清理状态 @param retentionUntil 保留截止 @param createdAt 创建时间 */
    record AttachmentStoredRecord(String attachmentId, String tenantId, Long storeId, String ticketId,
                                  String objectKey, String fileName, String mediaType, Long sizeBytes,
                                  String sha256, String state, LocalDateTime retentionUntil, LocalDateTime createdAt) { }
    /** @param tenantId 可信租户 @param ticketId 工单ID @param attachmentId 附件ID @param fromState 原状态 @param toState 目标状态 @param cleanedAt 清理时间 @param updatedAt 更新时间 */
    record AttachmentStateChange(String tenantId, String ticketId, String attachmentId, String fromState,
                                 String toState, LocalDateTime cleanedAt, LocalDateTime updatedAt) { }
    /** @param commandId 命令结果ID @param tenantId 可信租户 @param operationCode 操作编码 @param idempotencyKey 幂等键 @param requestSha256 请求摘要 @param resultType 结果类型 @param resultId 结果ID @param resultState 结果状态 @param createdAt 创建时间 */
    record CommandRecord(String commandId, String tenantId, String operationCode, String idempotencyKey,
                         String requestSha256, String resultType, String resultId, String resultState,
                         LocalDateTime createdAt) { }
    /** @param commandId 命令结果ID @param tenantId 可信租户 @param operationCode 操作编码 @param idempotencyKey 幂等键 @param requestSha256 请求摘要 @param resultType 结果类型 @param resultId 结果ID @param resultState 结果状态 @param createdAt 创建时间 */
    record CommandWrite(String commandId, String tenantId, String operationCode, String idempotencyKey,
                        String requestSha256, String resultType, String resultId, String resultState,
                        LocalDateTime createdAt) { }
    /** @param auditId 审计ID @param tenantId 可信租户 @param storeId 门店 @param aggregateType 聚合类型 @param aggregateId 聚合ID @param actionCode 动作 @param resultCode 结果 @param requestSha256 请求摘要 @param correlationId 关联标识 @param actorUserId 操作人 @param summary 摘要 @param occurredAt 发生时间 */
    record AuditWrite(String auditId, String tenantId, Long storeId, String aggregateType, String aggregateId,
                      String actionCode, String resultCode, String requestSha256, String correlationId,
                      Long actorUserId, String summary, LocalDateTime occurredAt) { }
    /** @param eventId 事件ID @param tenantId 可信租户 @param aggregateType 聚合类型 @param aggregateId 聚合ID @param aggregateVersion 聚合版本 @param eventType 事件类型 @param payloadJson 事件正文 @param payloadSha256 正文摘要 @param correlationId 关联标识 @param occurredAt 发生时间 */
    record OutboxWrite(String eventId, String tenantId, String aggregateType, String aggregateId,
                       Integer aggregateVersion, String eventType, String payloadJson, String payloadSha256,
                       String correlationId, LocalDateTime occurredAt) { }
}
