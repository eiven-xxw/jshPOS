package com.jingshanghui.pos.resilience.interfaces.rest.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

/** 备份恢复管理API请求；不包含tenantIds、密钥材料或恢复对象内容。 */
public final class BackupRequests {
    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private BackupRequests() { }

    /** @param backupId 备份ULID @param environment 环境 @param pointInTime 恢复点 @param latestIncludedFactAt 最后纳入事实 @param schemaVersion Schema版本 @param applicationVersion 应用版本 @param keyVersion 外部密钥版本引用 @param immutableUntil 保留截止 @param correlationId 关联ULID */
    public record Create(@Pattern(regexp=ULID) String backupId, @NotBlank @Size(max=64) String environment,
                         @NotNull Instant pointInTime, @NotNull Instant latestIncludedFactAt,
                         @NotBlank @Size(max=64) String schemaVersion,
                         @NotBlank @Size(max=64) String applicationVersion,
                         @NotBlank @Size(max=64) String keyVersion, @NotNull Instant immutableUntil,
                         @Pattern(regexp=ULID) String correlationId) { }

    /** @param drillId 演练ULID @param expectedSchemaVersion 期望Schema版本 @param correlationId 关联ULID */
    public record Restore(@Pattern(regexp=ULID) String drillId,
                          @NotBlank @Size(max=64) String expectedSchemaVersion,
                          @Pattern(regexp=ULID) String correlationId) { }
}
