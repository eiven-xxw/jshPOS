package com.jingshanghui.pos.operations.interfaces.rest.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

/** 异常中心 REST 请求；tenant、来源摘要、严重级别和 Owner 结果均不允许客户端提交。 */
public final class ExceptionCenterRequests {
    private ExceptionCenterRequests() { }
    /**
     * 来源扫描请求。
     * @param storeId 申请扫描的授权门店
     * @param businessDate 申请扫描的门店业务日
     */
    public record Scan(@NotNull @Positive Long storeId, @NotNull LocalDate businessDate) { }
    /**
     * 认领租约请求。
     * @param leaseMinutes 认领租约分钟数
     */
    public record Claim(@Min(5) @Max(120) int leaseMinutes) { }
    /**
     * 带原因的案件操作请求。
     * @param reason 去敏且可审计的操作原因
     */
    public record Reason(@NotBlank @Size(min=8,max=256) String reason) { }
    /**
     * 案件转派请求。
     * @param assigneeUserId 目标员工主键
     * @param leaseMinutes 新租约分钟数
     * @param reason 去敏转派原因
     */
    public record Transfer(@NotNull @Positive Long assigneeUserId, @Min(5) @Max(120) int leaseMinutes,
                           @NotBlank @Size(min=8,max=256) String reason) { }
    /**
     * 处置计划请求。
     * @param actionCode Owner 允许的具名修复动作
     * @param planSummary 去敏处置计划
     */
    public record Plan(@NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,64}$") String actionCode,
                       @NotBlank @Size(min=8,max=256) String planSummary) { }
    /**
     * Owner 修复请求。
     * @param actionCode 已批准计划绑定的 Owner 具名修复动作
     */
    public record Repair(@NotBlank @Pattern(regexp="^[A-Za-z0-9._:-]{1,64}$") String actionCode) { }
}
