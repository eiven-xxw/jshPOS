package com.jingshanghui.pos.migration.interfaces.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;

/** 开业资料迁移 REST 输入；禁止出现 tenant_id、会员身份或 Owner 事实字段。 */
public final class BusinessMigrationRequests {
    private BusinessMigrationRequests() { }
    /**
     * 创建批次协议输入。
     * @param dataTypes 必需资料类型集合
     * @param idempotencyKey 创建批次幂等键
     * @param correlationId 全链路关联标识
     */
    public record Create(@NotEmpty @Size(max=4) Set<String> dataTypes,
                         @NotBlank @Size(max=128) String idempotencyKey,
                         @NotBlank @Size(max=64) String correlationId) { }
    /**
     * 需要操作原因的审批协议输入。
     * @param idempotencyKey 审批动作幂等键
     * @param reason 受审计审批原因
     * @param correlationId 全链路关联标识
     */
    public record Action(@NotBlank @Size(max=128) String idempotencyKey,
                         @NotBlank @Size(max=256) String reason,
                         @NotBlank @Size(max=64) String correlationId) { }
    /**
     * 恢复、对账、激活或清理协议输入。
     * @param idempotencyKey 具名动作幂等键
     * @param reason 可空操作原因
     * @param correlationId 全链路关联标识
     */
    public record Run(@NotBlank @Size(max=128) String idempotencyKey,
                      @Size(max=256) String reason,
                      @NotBlank @Size(max=64) String correlationId) { }
    /**
     * 与 Multipart 原文件分离且可安全审计的上传元数据。
     * @param dataType 资料类型
     * @param mappingVersion 冻结映射版本
     * @param charset CSV 字符集或 XLSX 标识
     * @param sourceSystem 来源系统说明
     * @param custodyReference 原文件受控保管引用
     * @param declaredSha256 调用方声明的原文件 SHA-256
     * @param correlationId 全链路关联标识
     */
    public record UploadMetadata(@NotBlank @Pattern(regexp="CATALOG|SUPPLIER|OPENING_INVENTORY|MEMBER") String dataType,
                                 @NotBlank @Pattern(regexp="1\\.0") String mappingVersion,
                                 @NotBlank @Pattern(regexp="UTF-8|GB18030|XLSX") String charset,
                                 @NotBlank @Size(max=80) String sourceSystem,
                                 @NotBlank @Size(max=256) String custodyReference,
                                 @NotBlank @Pattern(regexp="^[a-f0-9]{64}$") String declaredSha256,
                                 @NotBlank @Size(max=64) String correlationId) { }
}
