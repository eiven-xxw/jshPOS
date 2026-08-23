package com.jingshanghui.pos.member.domain;

import java.util.Set;

/** T2-MEM-003 会员权益版本的具名状态与合法迁移。 */
public final class BenefitStates {
    public enum VersionState { DRAFT, VALIDATED, APPROVED, SCHEDULED, ACTIVE, PAUSED, RETIRED, REVOKED }

    /** 后端只允许显式白名单迁移，禁止客户端直接设置状态。 */
    public static boolean canTransition(VersionState from, VersionState to) {
        return switch (from) {
            case DRAFT -> to == VersionState.VALIDATED;
            case VALIDATED -> to == VersionState.APPROVED;
            case APPROVED -> Set.of(VersionState.SCHEDULED, VersionState.ACTIVE).contains(to);
            case SCHEDULED -> Set.of(VersionState.ACTIVE, VersionState.REVOKED).contains(to);
            case ACTIVE -> Set.of(VersionState.PAUSED, VersionState.RETIRED, VersionState.REVOKED).contains(to);
            case PAUSED -> Set.of(VersionState.ACTIVE, VersionState.RETIRED, VersionState.REVOKED).contains(to);
            case RETIRED, REVOKED -> false;
        };
    }

    private BenefitStates() { }
}
