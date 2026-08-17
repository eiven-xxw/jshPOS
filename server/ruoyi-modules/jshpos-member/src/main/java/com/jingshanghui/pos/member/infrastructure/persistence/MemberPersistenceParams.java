package com.jingshanghui.pos.member.infrastructure.persistence;

import java.time.LocalDateTime;

/** 将多字段更新封装为显式参数，避免 Mapper 参数位置歧义。 */
public final class MemberPersistenceParams {
    public record MemberStateUpdate(String tenantId, String memberId, String fromState, String toState,
                                    int expectedVersion) { }
    public record IdentityRevoke(String tenantId, String identityId, String memberId, Long actorUserId,
                                 LocalDateTime occurredAt) { }
    public record PrivacyStateUpdate(String tenantId, String requestId, String fromState, String toState,
                                     int expectedVersion, LocalDateTime completedAt) { }
    private MemberPersistenceParams() { }
}
