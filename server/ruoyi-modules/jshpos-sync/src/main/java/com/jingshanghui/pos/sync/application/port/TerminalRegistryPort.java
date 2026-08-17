package com.jingshanghui.pos.sync.application.port;

import com.jingshanghui.pos.sync.application.model.TerminalModels.ActivationRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.CredentialRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.DeviceAuthRecord;
import com.jingshanghui.pos.sync.application.model.TerminalModels.StoredCommand;
import com.jingshanghui.pos.sync.application.model.TerminalModels.TerminalView;

import java.time.LocalDateTime;
import java.util.List;

/** 终端登记复杂事实持久化端口；除激活 ID 窄化查询外，所有调用必须显式携带可信 tenant_id。 */
public interface TerminalRegistryPort {
    /**
     * 激活授权写入参数。
     * @param activationId 激活授权ULID
     * @param tenantId 可信租户标识
     * @param orgUnitId 绑定组织ID
     * @param storeId 绑定门店ID
     * @param boundUserId 绑定服务用户ID
     * @param terminalProfileCode 终端模板代码
     * @param secretHmac 激活秘密HMAC摘要
     * @param expiresAt UTC失效时间
     * @param idempotencyKey 签发幂等键
     * @param requestSha256 签发请求摘要
     * @param evidenceLevel 证据等级
     * @param createdBy 签发用户ID
     * @param createdAt UTC签发时间
     */
    record ActivationWrite(String activationId, String tenantId, Long orgUnitId, Long storeId,
                           Long boundUserId, String terminalProfileCode, String secretHmac,
                           LocalDateTime expiresAt, String idempotencyKey, String requestSha256,
                           String evidenceLevel, Long createdBy, LocalDateTime createdAt) { }
    /**
     * 终端主记录写入参数。
     * @param deviceId 服务端终端ULID
     * @param tenantId 激活授权租户
     * @param orgUnitId 激活授权组织ID
     * @param storeId 激活授权门店ID
     * @param terminalId 终端业务标识
     * @param boundUserId 绑定服务用户ID
     * @param activationId 激活授权ULID
     * @param terminalProfileCode 终端模板代码
     * @param fingerprintSha256 指纹摘要
     * @param publicKeySha256 公钥摘要
     * @param credentialVersion 初始凭据版本
     * @param appVersion 应用版本
     * @param schemaVersion Schema版本
     * @param capabilitySha256 能力摘要
     * @param clockSkewSeconds 时钟偏移秒数
     * @param evidenceLevel 证据等级
     * @param activatedAt UTC激活时间
     */
    record DeviceWrite(String deviceId, String tenantId, Long orgUnitId, Long storeId, String terminalId,
                       Long boundUserId, String activationId, String terminalProfileCode,
                       String fingerprintSha256, String publicKeySha256, long credentialVersion,
                       String appVersion, String protocolVersion, String schemaVersion,
                       String capabilitySha256, long clockSkewSeconds, String evidenceLevel,
                       LocalDateTime activatedAt) { }
    /**
     * 设备凭据写入参数。
     * @param credentialId 凭据记录ULID
     * @param tenantId 租户标识
     * @param deviceId 终端ULID
     * @param credentialVersion 凭据版本
     * @param secretHmac 设备秘密HMAC摘要
     * @param fingerprintSha256 指纹摘要
     * @param publicKeySha256 公钥摘要
     * @param issuedAt UTC签发时间
     * @param expiresAt UTC失效时间
     */
    record CredentialWrite(String credentialId, String tenantId, String deviceId, long credentialVersion,
                           String secretHmac, String fingerprintSha256, String publicKeySha256,
                           LocalDateTime issuedAt, LocalDateTime expiresAt) { }
    /**
     * 能力快照写入参数。
     * @param snapshotId 快照ULID
     * @param tenantId 租户标识
     * @param deviceId 终端ULID
     * @param sequenceNo 终端内序号
     * @param appVersion 应用版本
     * @param protocolVersion 协议版本
     * @param schemaVersion Schema版本
     * @param capabilityJson 规范化能力JSON
     * @param capabilitySha256 能力摘要
     * @param clientTime 客户端UTC时间
     * @param clockSkewSeconds 时钟偏移秒数
     * @param reportedAt 服务端UTC接收时间
     */
    record CapabilityWrite(String snapshotId, String tenantId, String deviceId, long sequenceNo,
                           String appVersion, String protocolVersion, String schemaVersion,
                           String capabilityJson, String capabilitySha256, LocalDateTime clientTime,
                           long clockSkewSeconds, LocalDateTime reportedAt) { }
    /**
     * 终端审计追加参数。
     * @param auditEventId 审计事件ULID
     * @param tenantId 租户标识
     * @param deviceId 终端ULID
     * @param storeId 门店ID
     * @param actionCode 安全动作代码
     * @param beforeStatus 原状态
     * @param afterStatus 新状态
     * @param evidenceSha256 去敏证据摘要
     * @param actorType 操作者类型
     * @param actorId 操作者内部标识
     * @param reason 受审计原因
     * @param correlationId 关联标识
     * @param occurredAt UTC发生时间
     */
    record AuditWrite(String auditEventId, String tenantId, String deviceId, Long storeId,
                      String actionCode, String beforeStatus, String afterStatus, String evidenceSha256,
                      String actorType, String actorId, String reason, String correlationId,
                      LocalDateTime occurredAt) { }
    /**
     * 命令幂等结果追加参数。
     * @param commandResultId 命令结果ULID
     * @param tenantId 租户标识
     * @param commandType 命令类型
     * @param idempotencyKey 幂等键
     * @param requestSha256 请求摘要
     * @param aggregateId 影响对象ULID
     * @param resultCode 稳定结果代码
     * @param resultJson 去除秘密的结果JSON
     * @param resultSha256 结果摘要
     * @param createdAt UTC创建时间
     */
    record CommandWrite(String commandResultId, String tenantId, String commandType, String idempotencyKey,
                        String requestSha256, String aggregateId, String resultCode,
                        String resultJson, String resultSha256, LocalDateTime createdAt) { }

    ActivationRecord findActivationByCommand(String tenantId, String idempotencyKey);
    ActivationRecord lockActivationById(String activationId);
    int insertActivation(ActivationWrite value);
    int cancelActivation(String tenantId, String activationId, Long actorId, LocalDateTime at);
    int consumeActivation(String activationId, String deviceId, long expectedVersion, LocalDateTime at);
    int insertDevice(DeviceWrite value);
    TerminalView findDevice(String tenantId, String deviceId);
    TerminalView lockDevice(String tenantId, String deviceId);
    DeviceAuthRecord lockDeviceForAuthentication(String deviceId);
    List<TerminalView> listDevices(String tenantId, Long storeId, int offset, int limit);
    long countDevices(String tenantId, Long storeId);
    int changeStatus(StatusChange value);
    int updateCredentialVersion(CredentialVersionChange value);
    int insertCredential(CredentialWrite value);
    CredentialRecord findActiveCredential(String tenantId, String deviceId);
    int invalidateActiveCredential(String tenantId, String deviceId, String targetStatus, LocalDateTime at);
    long nextCapabilitySequence(String tenantId, String deviceId);
    String findCapabilityDigest(String tenantId, String deviceId, String capabilitySha256);
    int insertCapability(CapabilityWrite value);
    int updateDeviceCapability(DeviceCapabilityChange value);
    StoredCommand findCommand(String tenantId, String commandType, String idempotencyKey);
    int insertCommand(CommandWrite value);
    int insertAudit(AuditWrite value);
    /**
     * 终端状态受控变更参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param fromStatus 原状态
     * @param toStatus 目标状态
     * @param reason 受审计原因
     * @param expectedVersion 乐观锁版本
     * @param at UTC变更时间
     */
    record StatusChange(String tenantId, String deviceId, String fromStatus, String toStatus,
                        String reason, long expectedVersion, LocalDateTime at) { }

    /**
     * 凭据版本条件更新参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param fromVersion 原凭据版本
     * @param toVersion 新凭据版本
     * @param at UTC轮换时间
     */
    record CredentialVersionChange(String tenantId, String deviceId, long fromVersion,
                                   long toVersion, LocalDateTime at) { }

    /**
     * 终端当前能力受控更新参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param appVersion 应用版本
     * @param protocolVersion 协议版本
     * @param schemaVersion Schema版本
     * @param capabilitySha256 能力摘要
     * @param clockSkewSeconds 时钟偏移秒数
     * @param at UTC更新时间
     */
    record DeviceCapabilityChange(String tenantId, String deviceId, String appVersion,
                                  String schemaVersion, String capabilitySha256,
                                  long clockSkewSeconds, LocalDateTime at) { }
}
