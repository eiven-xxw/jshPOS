package com.jingshanghui.pos.sync.infrastructure.persistence;

import java.time.LocalDateTime;

/** XML Mapper 的多参数命令对象，避免位置参数误绑和租户条件遗漏。 */
public final class TerminalPersistenceParams {
    private TerminalPersistenceParams() { }

    /**
     * 取消激活SQL参数。
     * @param tenantId 可信租户标识
     * @param activationId 激活授权ULID
     * @param actorId 操作用户ID
     * @param at UTC取消时间
     */
    public record CancelActivation(String tenantId, String activationId, Long actorId, LocalDateTime at) { }
    /**
     * 消费激活SQL参数。
     * @param activationId 激活授权ULID
     * @param deviceId 新终端ULID
     * @param expectedVersion 授权乐观锁版本
     * @param at UTC消费时间
     */
    public record ConsumeActivation(String activationId, String deviceId, long expectedVersion, LocalDateTime at) { }
    /**
     * 状态条件更新SQL参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param fromStatus 原状态
     * @param toStatus 目标状态
     * @param reason 受审计原因
     * @param expectedVersion 乐观锁版本
     * @param at UTC变更时间
     */
    public record StatusUpdate(String tenantId, String deviceId, String fromStatus, String toStatus,
                               String reason, long expectedVersion, LocalDateTime at) { }
    /**
     * 凭据版本条件更新SQL参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param fromVersion 原凭据版本
     * @param toVersion 新凭据版本
     * @param at UTC轮换时间
     */
    public record CredentialVersionUpdate(String tenantId, String deviceId, long fromVersion,
                                          long toVersion, LocalDateTime at) { }
    /**
     * 当前能力条件更新SQL参数。
     * @param tenantId 可信租户标识
     * @param deviceId 终端ULID
     * @param appVersion 应用版本
     * @param schemaVersion Schema版本
     * @param capabilitySha256 能力摘要
     * @param clockSkewSeconds 时钟偏移秒数
     * @param at UTC更新时间
     */
    public record CapabilityUpdate(String tenantId, String deviceId, String appVersion,
                                   String schemaVersion, String capabilitySha256,
                                   long clockSkewSeconds, LocalDateTime at) { }
}
