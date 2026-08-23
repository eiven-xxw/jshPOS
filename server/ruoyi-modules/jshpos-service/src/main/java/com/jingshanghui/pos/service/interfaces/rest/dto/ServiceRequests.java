package com.jingshanghui.pos.service.interfaces.rest.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.util.List;

/** T2-SVC-001 HTTP 入站 DTO；不接收 tenant_id、状态结果、对象键或授权结论。 */
public final class ServiceRequests {
    private ServiceRequests() { }

    /** @param itemCode 检查项编码 @param itemName 检查项名称 @param mandatory 是否必选 @param sequenceNo 显示顺序 */
    public record CatalogItem(@NotBlank @Size(max = 64) String itemCode,
                              @NotBlank @Size(max = 120) String itemName,
                              @NotNull Boolean mandatory,
                              @NotNull @Min(1) @Max(1000) Integer sequenceNo) { }

    /** @param catalogCode 目录编码 @param versionNo 版本号 @param industryTemplate 行业模板 @param name 目录名称 @param items 检查项 */
    public record CreateCatalog(@NotBlank @Size(max = 64) String catalogCode,
                                @NotNull @Min(1) Integer versionNo,
                                @NotBlank @Size(max = 64) String industryTemplate,
                                @NotBlank @Size(max = 120) String name,
                                @NotEmpty @Size(max = 100) List<@Valid CatalogItem> items) { }

    /** @param storeId 可信权限范围内的门店 @param catalogId 已发布服务目录 @param targetDate 内部目标日期 @param ownerUserId 负责人 */
    public record CreateProject(@NotNull @Positive Long storeId,
                                @NotBlank @Pattern(regexp = "^[0-9A-HJKMNP-TV-Z]{26}$") String catalogId,
                                @NotNull LocalDate targetDate,
                                @Positive Long ownerUserId) { }

    /** @param command 具名状态命令 @param reason 操作原因 */
    public record StateCommand(@NotBlank @Size(max = 64) String command,
                               @NotBlank @Size(max = 500) String reason) { }

    /** @param reason 检查项完成或操作说明 */
    public record Reason(@NotBlank @Size(max = 500) String reason) { }

    /** @param storeId 可信权限范围内的门店 @param projectId 可选实施项目 @param serviceType 服务类型 @param priority 优先级 @param subject 工单主题 @param description 问题描述 @param internalTargetMinutes 内部响应目标分钟数 */
    public record CreateTicket(@NotNull @Positive Long storeId,
                               @Pattern(regexp = "^[0-9A-HJKMNP-TV-Z]{26}$") String projectId,
                               @NotBlank @Size(max = 64) String serviceType,
                               @NotBlank @Size(max = 8) String priority,
                               @NotBlank @Size(max = 200) String subject,
                               @Size(max = 2000) String description,
                               @NotNull @Min(1) @Max(525600) Integer internalTargetMinutes) { }

    /** @param command 具名工单命令 @param assigneeUserId 目标责任人 @param leaseMinutes 认领租约分钟数 @param reason 操作原因 @param resolutionSummary 解决摘要 */
    public record TicketCommand(@NotBlank @Size(max = 64) String command,
                                @Positive Long assigneeUserId,
                                @Min(1) @Max(1440) Integer leaseMinutes,
                                @NotBlank @Size(max = 1000) String reason,
                                @Size(max = 1000) String resolutionSummary) { }
}
