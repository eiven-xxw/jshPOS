package com.jingshanghui.pos.service.application.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** T2-SVC-001 应用层不可变 Command、只读投影与响应模型。 */
public final class ServiceModels {
    private ServiceModels() { }

    /** @param itemCode 检查项编码 @param itemName 检查项名称 @param mandatory 是否必选 @param sequenceNo 显示顺序 */
    public record CatalogItemInput(String itemCode, String itemName, Boolean mandatory, Integer sequenceNo) { }
    /** @param catalogCode 目录编码 @param versionNo 版本号 @param industryTemplate 三业态模板 @param name 名称 @param items 检查模板 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record CreateCatalog(String catalogCode, Integer versionNo, String industryTemplate, String name,
                                List<CatalogItemInput> items, String idempotencyKey, String correlationId) { }
    /** @param catalogId 目录ID @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record CatalogCommand(String catalogId, String idempotencyKey, String correlationId) { }
    /** @param catalogId 目录ID @param tenantId 可信租户 @param catalogCode 编码 @param versionNo 版本 @param industryTemplate 业态 @param name 名称 @param state 状态 @param contentSha256 摘要 @param recordVersion 乐观版本 */
    public record CatalogRecord(String catalogId, String tenantId, String catalogCode, Integer versionNo,
                                String industryTemplate, String name, String state, String contentSha256, Integer recordVersion) { }
    /** @param itemId 项ID @param itemCode 编码 @param itemName 名称 @param mandatory 必选 @param sequenceNo 顺序 */
    public record CatalogItemRecord(String itemId, String itemCode, String itemName, Boolean mandatory, Integer sequenceNo) { }
    /** @param catalog 目录 @param items 检查模板 */
    public record CatalogDetail(CatalogRecord catalog, List<CatalogItemRecord> items) { }

    /** @param storeId 门店 @param catalogId 已发布目录 @param targetDate 目标日期 @param ownerUserId 负责人 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record CreateProject(Long storeId, String catalogId, LocalDate targetDate, Long ownerUserId,
                                String idempotencyKey, String correlationId) { }
    /** @param projectId 项目ID @param command 命令 @param reason 原因 @param expectedVersion 版本 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record ProjectCommand(String projectId, String command, String reason, Integer expectedVersion,
                                 String idempotencyKey, String correlationId) { }
    /** @param projectId 项目ID @param checkId 检查项ID @param reason 完成说明 @param expectedVersion 版本 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record CompleteCheck(String projectId, String checkId, String reason, Integer expectedVersion,
                                String idempotencyKey, String correlationId) { }
    /** @param projectId 项目ID @param tenantId 租户 @param storeId 门店 @param catalogId 目录 @param state 状态 @param ownerUserId 负责人 @param targetDate 目标日期 @param recordVersion 版本 @param contentSha256 摘要 */
    public record ProjectRecord(String projectId, String tenantId, Long storeId, String catalogId, String state,
                                Long ownerUserId, LocalDate targetDate, Integer recordVersion, String contentSha256) { }
    /** @param checkId 检查项ID @param itemCode 编码 @param itemName 名称 @param mandatory 必选 @param state 状态 @param completedBy 完成人 @param completedAt 完成时间 @param recordVersion 版本 */
    public record CheckRecord(String checkId, String itemCode, String itemName, Boolean mandatory, String state,
                              Long completedBy, LocalDateTime completedAt, Integer recordVersion) { }
    /** @param project 项目 @param checks 冻结检查项 */
    public record ProjectDetail(ProjectRecord project, List<CheckRecord> checks) { }

    /** @param storeId 门店 @param projectId 可选实施项目 @param serviceType 服务类型 @param priority 优先级 @param subject 主题 @param description 描述 @param internalTargetMinutes 内部目标分钟 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record CreateTicket(Long storeId, String projectId, String serviceType, String priority, String subject,
                               String description, Integer internalTargetMinutes, String idempotencyKey, String correlationId) { }
    /** @param ticketId 工单ID @param command 命令 @param assigneeUserId 目标责任人 @param leaseMinutes 租约分钟 @param reason 原因 @param resolutionSummary 解决摘要 @param expectedVersion 版本 @param idempotencyKey 幂等键 @param correlationId 关联标识 */
    public record TicketCommand(String ticketId, String command, Long assigneeUserId, Integer leaseMinutes,
                                String reason, String resolutionSummary, Integer expectedVersion,
                                String idempotencyKey, String correlationId) { }
    /** @param ticketId 工单ID @param tenantId 租户 @param storeId 门店 @param projectId 项目 @param serviceType 类型 @param priority 优先级 @param subject 主题 @param description 描述 @param state 状态 @param assigneeUserId 责任人 @param leaseUntil 租约截止 @param resolvedBy 解决人 @param closedBy 复核关闭人 @param resolutionSummary 解决摘要 @param targetAt 内部目标时间 @param recordVersion 版本 @param contentSha256 摘要 */
    public record TicketRecord(String ticketId, String tenantId, Long storeId, String projectId, String serviceType,
                               String priority, String subject, String description, String state, Long assigneeUserId,
                               LocalDateTime leaseUntil, Long resolvedBy, Long closedBy, String resolutionSummary,
                               LocalDateTime targetAt, Integer recordVersion, String contentSha256) { }
    /** @param attachmentId 附件ID @param fileName 安全文件名 @param mediaType 媒体类型 @param sizeBytes 字节数 @param sha256 内容摘要 @param state 清理状态 @param createdAt 上传时间 */
    public record AttachmentRecord(String attachmentId, String fileName, String mediaType, Long sizeBytes,
                                   String sha256, String state, LocalDateTime createdAt) { }
    /** @param ticket 工单 @param attachments 附件元数据 @param overdue 是否超过内部目标 */
    public record TicketDetail(TicketRecord ticket, List<AttachmentRecord> attachments, boolean overdue) { }
    /** @param attachment 附件元数据 @param downloadUrl 短期地址 @param expiresAt 到期时间 */
    public record AttachmentDownload(AttachmentRecord attachment, String downloadUrl, LocalDateTime expiresAt) { }
}
