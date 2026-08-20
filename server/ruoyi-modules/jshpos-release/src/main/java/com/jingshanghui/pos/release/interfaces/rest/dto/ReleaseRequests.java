package com.jingshanghui.pos.release.interfaces.rest.dto;

import com.jingshanghui.pos.release.domain.ReleaseModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.Set;

/** Gate 6B 管理API输入；不包含tenant_id、终端门店、认证状态或安全探针结果。 */
public final class ReleaseRequests {
    private ReleaseRequests() { }
    public static final String IDEMPOTENCY = "^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$";

    /** 创建受控发布草稿。 */
    public record Create(@NotNull ArtifactType artifactType, @NotBlank @Size(max=64) String version,
                         @NotNull Channel channel, @NotBlank @Size(max=512) String objectKey,
                         @Pattern(regexp="^[0-9a-f]{64}$") String artifactSha256,
                         @NotBlank @Size(min=32,max=1024) String signatureBase64,
                         @NotBlank @Size(max=64) String keyVersion,
                         @Pattern(regexp="^[0-9a-f]{40}$") String buildCommit,
                         @Pattern(regexp="^[0-9a-f]{64}$") String sbomSha256,
                         @Valid @NotNull Compatibility compatibility,
                         @NotEmpty @Size(max=10000) Set<@Positive Long> targetStoreIds) { }
    /** 兼容窗口输入。 */
    public record Compatibility(@NotBlank @Size(max=32) String minAppVersion,
                                @NotBlank @Size(max=32) String maxAppVersion,
                                @NotBlank @Size(max=32) String minProtocolVersion,
                                @NotBlank @Size(max=32) String maxProtocolVersion,
                                @NotBlank @Size(max=32) String minSchemaVersion,
                                @NotBlank @Size(max=32) String maxSchemaVersion,
                                @NotBlank @Size(max=32) String minSystemVersion,
                                @NotBlank @Size(max=32) String maxSystemVersion,
                                @Pattern(regexp="^$|^[0-9a-f]{64}$") String requiredCapabilitySha256) {
        public CompatibilityWindow toDomain() {
            return new CompatibilityWindow(minAppVersion,maxAppVersion,minProtocolVersion,maxProtocolVersion,
                minSchemaVersion,maxSchemaVersion,minSystemVersion,maxSystemVersion,requiredCapabilitySha256);
        }
    }
    /** 创建灰度批次。 */
    public record Rollout(@NotEmpty @Size(max=10000) Set<@Positive Long> targetStoreIds,
                          @Min(1) @Max(25) int canaryPercent) { }
    /** 分配已登记终端。 */
    public record Assign(@Pattern(regexp="^[0-9A-HJKMNP-TV-Z]{26}$") String deviceId) { }
    /** 记录软件执行观察。 */
    public record Observe(@NotNull ObservationType type,
                          @Pattern(regexp="^[0-9a-f]{64}$") String artifactSha256,
                          @Pattern(regexp="^[0-9a-f]{64}$") String evidenceSha256) { }
}
