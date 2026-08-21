package com.jingshanghui.pos.sync.interfaces.rest.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/** Gate 6A 终端接口输入；不定义 tenant_id、组织或门店的客户端覆盖字段。 */
public final class TerminalRequests {
    public static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    public static final String SHA256 = "^[a-f0-9]{64}$";
    public static final String VERSION = "^[0-9]+(?:\\.[0-9]+){0,3}$";
    public static final String IDEMPOTENCY = "^[A-Za-z0-9][A-Za-z0-9._:-]{15,63}$";

    private TerminalRequests() { }

    /**
     * 签发激活接口请求。
     * @param orgUnitId 受权组织ID
     * @param storeId 受权门店ID
     * @param boundUserId 绑定服务用户ID
     * @param terminalProfileCode 终端模板代码
     * @param expiresInSeconds 有效秒数
     * @param idempotencyKey 签发幂等键
     */
    public record IssueActivation(@NotNull @Min(1) Long orgUnitId, @NotNull @Min(1) Long storeId,
                                  @NotNull @Min(1) Long boundUserId,
                                  @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_-]{1,63}$") String terminalProfileCode,
                                  @Min(60) @Max(86400) long expiresInSeconds,
                                  @NotBlank @Pattern(regexp = IDEMPOTENCY) String idempotencyKey) { }

    /**
     * POS激活接口请求，不包含租户、组织或门店覆盖字段。
     * @param activationId 激活授权ULID
     * @param activationSecret 一次性激活秘密
     * @param deviceFingerprintSha256 设备指纹摘要
     * @param publicKeySha256 设备公钥摘要
     * @param appVersion 应用版本
     * @param protocolVersion 协议版本
     * @param schemaVersion Schema版本
     * @param capability 能力声明
     * @param idempotencyKey 激活幂等键
     * @param clientTime 客户端UTC时间
     */
    public record Activate(@NotBlank @Pattern(regexp = ULID) String activationId,
                           @NotBlank @Size(min = 32, max = 128) String activationSecret,
                           @NotBlank @Pattern(regexp = SHA256) String deviceFingerprintSha256,
                           @NotBlank @Pattern(regexp = SHA256) String publicKeySha256,
                           @NotBlank @Pattern(regexp = VERSION) String appVersion,
                           @NotBlank @Pattern(regexp = VERSION) String protocolVersion,
                           @NotBlank @Pattern(regexp = VERSION) String schemaVersion,
                           @NotEmpty Map<String, Object> capability,
                           @NotBlank @Pattern(regexp = IDEMPOTENCY) String idempotencyKey,
                           @NotNull Instant clientTime) { }

    /**
     * POS 设备凭据认证请求；不接收 tenant_id、门店或终端能力覆盖值。
     * @param deviceId 服务端激活时分配的设备 ULID
     * @param deviceCredential 仅用于本次内存校验的设备秘密
     * @param deviceFingerprintSha256 设备指纹摘要
     * @param publicKeySha256 设备公钥摘要
     * @param appVersion 应用版本
     * @param protocolVersion 同步协议版本
     * @param schemaVersion 本地 Schema 版本
     * @param clientTime 客户端 UTC 时间
     */
    public record Authenticate(@NotBlank @Pattern(regexp = ULID) String deviceId,
                               @NotBlank @Size(min = 32, max = 128) String deviceCredential,
                               @NotBlank @Pattern(regexp = SHA256) String deviceFingerprintSha256,
                               @NotBlank @Pattern(regexp = SHA256) String publicKeySha256,
                               @NotBlank @Pattern(regexp = VERSION) String appVersion,
                               @NotBlank @Pattern(regexp = VERSION) String protocolVersion,
                               @NotBlank @Pattern(regexp = VERSION) String schemaVersion,
                               @NotNull Instant clientTime) { }

    /**
     * 终端状态接口请求。
     * @param targetStatus 目标安全状态
     * @param reason 受审计原因
     * @param idempotencyKey 状态命令幂等键
     * @param expectedVersion 乐观锁版本
     */
    public record ChangeStatus(@NotBlank @Pattern(regexp = "^(ACTIVE|BLOCKED|REVOKED|RETIRED)$") String targetStatus,
                               @NotBlank @Size(min = 4, max = 256) String reason,
                               @NotBlank @Pattern(regexp = IDEMPOTENCY) String idempotencyKey,
                               @Min(1) long expectedVersion) { }

    /**
     * 终端能力上报接口请求。
     * @param appVersion 应用版本
     * @param protocolVersion 协议版本
     * @param schemaVersion Schema版本
     * @param capability 能力声明
     * @param idempotencyKey 上报幂等键
     * @param clientTime 客户端UTC时间
     */
    public record Capability(@NotBlank @Pattern(regexp = VERSION) String appVersion,
                             @NotBlank @Pattern(regexp = VERSION) String protocolVersion,
                             @NotBlank @Pattern(regexp = VERSION) String schemaVersion,
                             @NotEmpty Map<String, Object> capability,
                             @NotBlank @Pattern(regexp = IDEMPOTENCY) String idempotencyKey,
                             @NotNull Instant clientTime) { }
}
