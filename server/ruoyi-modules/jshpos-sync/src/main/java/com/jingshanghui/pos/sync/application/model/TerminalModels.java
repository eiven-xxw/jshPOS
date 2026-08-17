package com.jingshanghui.pos.sync.application.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Gate 6A 终端登记领域命令与只读视图。
 * 原始激活秘密和设备凭据仅允许出现在一次性响应对象中，禁止写入日志与持久化。
 */
public final class TerminalModels {
    private TerminalModels() { }

    /**
     * 后台签发终端激活授权命令。
     * @param orgUnitId 服务端授权范围内组织ID
     * @param storeId 服务端授权范围内门店ID
     * @param boundUserId 绑定的POS服务用户ID
     * @param terminalProfileCode 终端能力模板代码
     * @param expiresInSeconds 激活授权有效秒数
     * @param idempotencyKey 租户内幂等键
     */
    public record IssueActivationCommand(Long orgUnitId, Long storeId, Long boundUserId,
                                         String terminalProfileCode, long expiresInSeconds,
                                         String idempotencyKey) { }

    /**
     * POS 终端激活命令；tenant、组织和门店不由客户端提供。
     * @param activationId 服务端签发的激活授权ULID
     * @param activationSecret 只在内存中校验的一次性秘密
     * @param deviceFingerprintSha256 不可逆设备指纹摘要
     * @param publicKeySha256 设备公钥摘要
     * @param appVersion POS应用数字版本
     * @param protocolVersion 同步协议数字版本
     * @param schemaVersion POS本地Schema数字版本
     * @param capability 设备能力声明
     * @param idempotencyKey 激活命令幂等键
     * @param clientTime 客户端UTC时间，仅用于偏移测量
     */
    public record ActivateTerminalCommand(String activationId, String activationSecret,
                                          String deviceFingerprintSha256, String publicKeySha256,
                                          String appVersion, String protocolVersion, String schemaVersion,
                                          Map<String, Object> capability, String idempotencyKey,
                                          Instant clientTime) { }

    /**
     * 受权终端状态变更命令。
     * @param targetStatus 目标安全状态
     * @param reason 受审计变更原因
     * @param idempotencyKey 状态命令幂等键
     * @param expectedVersion 终端乐观锁版本
     */
    public record ChangeTerminalStatusCommand(String targetStatus, String reason,
                                              String idempotencyKey, long expectedVersion) { }

    /**
     * 已认证终端能力上报命令。
     * @param deviceId 服务端分配的终端ULID
     * @param appVersion POS应用数字版本
     * @param protocolVersion 同步协议数字版本
     * @param schemaVersion POS本地Schema数字版本
     * @param capability 设备能力声明
     * @param idempotencyKey 能力上报幂等键
     * @param clientTime 客户端UTC时间
     */
    public record ReportCapabilityCommand(String deviceId, String appVersion, String protocolVersion,
                                          String schemaVersion, Map<String, Object> capability,
                                          String idempotencyKey, Instant clientTime) { }

    /**
     * 设备凭据认证命令；tenant、组织和门店不允许由调用者提供。
     * @param deviceId 服务端分配的终端ULID
     * @param deviceCredential 只在内存中校验的设备秘密
     * @param deviceFingerprintSha256 设备指纹摘要
     * @param publicKeySha256 设备公钥摘要
     * @param appVersion POS应用数字版本
     * @param protocolVersion 同步协议数字版本
     * @param schemaVersion POS本地Schema数字版本
     * @param clientTime 客户端UTC时间
     */
    public record AuthenticateTerminalCommand(String deviceId, String deviceCredential,
                                              String deviceFingerprintSha256, String publicKeySha256,
                                              String appVersion, String protocolVersion,
                                              String schemaVersion, Instant clientTime) { }

    /**
     * 激活授权视图；secret 只在首次签发时非空。
     * @param activationId 激活授权ULID
     * @param activationSecret 仅首次响应返回的一次性秘密
     * @param expiresAt 服务端UTC失效时间
     * @param status 当前授权状态
     * @param secretShownOnce 本响应是否首次显示秘密
     */
    public record IssuedActivation(String activationId, String activationSecret, LocalDateTime expiresAt,
                                   String status, boolean secretShownOnce) { }

    /**
     * 激活结果；deviceCredential 只在首次成功激活时非空。
     * @param deviceId 服务端终端ULID
     * @param tenantId 由激活授权派生的租户标识
     * @param orgUnitId 由激活授权派生的组织ID
     * @param storeId 由激活授权派生的门店ID
     * @param terminalId 服务端终端业务标识
     * @param deviceCredential 仅首次响应返回的设备秘密
     * @param credentialVersion 当前凭据版本
     * @param status 当前终端状态
     * @param secretShownOnce 本响应是否首次显示秘密
     */
    public record ActivatedTerminal(String deviceId, String tenantId, Long orgUnitId, Long storeId,
                                    String terminalId, String deviceCredential, long credentialVersion,
                                    String status, boolean secretShownOnce) { }

    /**
     * 终端登记只读视图，不包含凭据摘要。
     * @param deviceId 终端ULID
     * @param orgUnitId 绑定组织ID
     * @param storeId 绑定门店ID
     * @param terminalId 终端业务标识
     * @param boundUserId 绑定服务用户ID
     * @param status 终端安全状态
     * @param terminalProfileCode 终端能力模板代码
     * @param appVersion POS应用版本
     * @param minProtocolVersion 最低协议版本
     * @param maxProtocolVersion 最高协议版本
     * @param schemaVersion POS本地Schema版本
     * @param capabilitySha256 当前能力快照摘要
     * @param credentialVersion 当前凭据版本
     * @param evidenceLevel 激活证据等级
     * @param recordVersion 乐观锁版本
     * @param activatedAt 服务端UTC激活时间
     * @param lastSeenAt 服务端UTC最后在线时间
     */
    public record TerminalView(String deviceId, Long orgUnitId, Long storeId, String terminalId,
                               Long boundUserId, String status, String terminalProfileCode,
                               String appVersion, String minProtocolVersion, String maxProtocolVersion,
                               String schemaVersion, String capabilitySha256, long credentialVersion,
                               String evidenceLevel, Long recordVersion, LocalDateTime activatedAt,
                               LocalDateTime lastSeenAt) { }

    /**
     * 激活授权持久化视图。
     * @param activationId 激活授权ULID
     * @param tenantId 租户标识
     * @param orgUnitId 绑定组织ID
     * @param storeId 绑定门店ID
     * @param boundUserId 绑定服务用户ID
     * @param terminalProfileCode 终端能力模板代码
     * @param secretHmac 激活秘密HMAC摘要
     * @param status 授权状态
     * @param expiresAt UTC失效时间
     * @param consumedDeviceId 消费后终端ULID
     * @param idempotencyKey 签发幂等键
     * @param requestSha256 签发请求摘要
     * @param evidenceLevel 证据等级
     * @param createdBy 签发用户ID
     * @param createdAt UTC签发时间
     * @param recordVersion 乐观锁版本
     */
    public record ActivationRecord(String activationId, String tenantId, Long orgUnitId, Long storeId,
                                   Long boundUserId, String terminalProfileCode, String secretHmac,
                                   String status, LocalDateTime expiresAt, String consumedDeviceId,
                                   String idempotencyKey, String requestSha256, String evidenceLevel,
                                   Long createdBy, LocalDateTime createdAt, Long recordVersion) { }

    /**
     * 设备凭据持久化视图。
     * @param credentialId 凭据记录ULID
     * @param tenantId 租户标识
     * @param deviceId 终端ULID
     * @param credentialVersion 凭据版本
     * @param secretHmac 设备秘密HMAC摘要
     * @param fingerprintSha256 设备指纹摘要
     * @param publicKeySha256 设备公钥摘要
     * @param status 凭据状态
     * @param expiresAt UTC失效时间
     */
    public record CredentialRecord(String credentialId, String tenantId, String deviceId,
                                   long credentialVersion, String secretHmac, String fingerprintSha256,
                                   String publicKeySha256, String status, LocalDateTime expiresAt) { }

    /**
     * 命令幂等结果，不保存原始秘密。
     * @param requestSha256 规范化命令摘要
     * @param aggregateId 影响的终端或授权ULID
     * @param resultCode 稳定结果代码
     * @param resultJson 去除秘密的结果JSON
     * @param resultSha256 结果摘要
     */
    public record StoredCommand(String requestSha256, String aggregateId,
                                String resultCode, String resultJson, String resultSha256) { }

    /**
     * 终端列表分页结果。
     * @param items 当前页终端视图
     * @param total 租户范围总数
     * @param page 一基页码
     * @param size 每页条数
     */
    public record TerminalPage(List<TerminalView> items, long total, int page, int size) { }

    /**
     * 新设备凭据只在首次轮换响应中非空。
     * @param deviceId 终端ULID
     * @param credentialVersion 新凭据版本
     * @param deviceCredential 仅首次响应显示的新秘密
     * @param secretShownOnce 本响应是否首次显示秘密
     */
    public record RotatedCredential(String deviceId, long credentialVersion,
                                    String deviceCredential, boolean secretShownOnce) { }

    /**
     * 应用层终端审计命令。
     * @param tenantId 可信或由激活派生的租户标识
     * @param deviceId 相关终端ULID
     * @param storeId 相关门店ID
     * @param action 安全动作代码
     * @param beforeStatus 原状态
     * @param afterStatus 新状态
     * @param evidenceSha256 去敏证据摘要
     * @param actorType 操作者类型
     * @param actorId 操作者内部标识
     * @param reason 受审计原因
     * @param occurredAt UTC发生时间
     */
    public record TerminalAuditCommand(String tenantId, String deviceId, Long storeId, String action,
                                       String beforeStatus, String afterStatus, String evidenceSha256,
                                       String actorType, String actorId, String reason,
                                       LocalDateTime occurredAt) { }

    /**
     * 无租户认证根查询到的内部终端视图，不得直接从 REST 返回。
     * @param tenantId 注册表租户标识
     * @param deviceId 终端ULID
     * @param orgUnitId 绑定组织ID
     * @param storeId 绑定门店ID
     * @param terminalId 终端业务标识
     * @param boundUserId 绑定服务用户ID
     * @param status 终端状态
     * @param fingerprintSha256 指纹摘要
     * @param publicKeySha256 公钥摘要
     * @param appVersion 已登记应用版本
     * @param minProtocolVersion 最低协议版本
     * @param maxProtocolVersion 最高协议版本
     * @param schemaVersion 已登记Schema版本
     * @param credentialVersion 当前凭据版本
     * @param recordVersion 终端乐观锁版本
     */
    public record DeviceAuthRecord(String tenantId, String deviceId, Long orgUnitId, Long storeId,
                                   String terminalId, Long boundUserId, String status,
                                   String fingerprintSha256, String publicKeySha256,
                                   String appVersion, String minProtocolVersion, String maxProtocolVersion,
                                   String schemaVersion, long credentialVersion, long recordVersion) { }

    /**
     * 已验证的设备身份；只能由凭据验证服务构造。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param orgUnitId 可信组织ID
     * @param storeId 可信门店ID
     * @param terminalId 终端业务标识
     * @param boundUserId 绑定服务用户ID
     * @param protocolVersion 已验证协议版本
     * @param schemaVersion 已验证Schema版本
     * @param credentialVersion 已验证凭据版本
     */
    public record AuthenticatedDevice(String tenantId, String deviceId, Long orgUnitId, Long storeId,
                                      String terminalId, Long boundUserId, String protocolVersion,
                                      String schemaVersion, long credentialVersion) { }
}
