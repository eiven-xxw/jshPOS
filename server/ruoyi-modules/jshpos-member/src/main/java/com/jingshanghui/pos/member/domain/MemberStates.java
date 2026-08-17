package com.jingshanghui.pos.member.domain;

import java.util.Set;

/** 会员、同意和隐私请求的受控状态集合。 */
public final class MemberStates {
    public enum MemberState { ACTIVE, SUSPENDED, MERGED, ANONYMIZED }
    public enum IdentityState { ACTIVE, REVOKED }
    public enum ConsentState { GRANTED, REVOKED }
    public enum PrivacyRequestState {
        REQUESTED, IDENTITY_VERIFIED, IN_PROGRESS, FULFILLED, PARTIALLY_FULFILLED, REJECTED
    }

    private MemberStates() { }

    /** 校验隐私请求状态机；完成、拒绝和取消都是终态。 */
    public static boolean canTransition(PrivacyRequestState from, PrivacyRequestState to) {
        return switch (from) {
            case REQUESTED -> Set.of(PrivacyRequestState.IDENTITY_VERIFIED, PrivacyRequestState.REJECTED).contains(to);
            case IDENTITY_VERIFIED -> Set.of(PrivacyRequestState.IN_PROGRESS, PrivacyRequestState.REJECTED).contains(to);
            case IN_PROGRESS -> Set.of(PrivacyRequestState.FULFILLED,
                PrivacyRequestState.PARTIALLY_FULFILLED, PrivacyRequestState.REJECTED).contains(to);
            case FULFILLED, PARTIALLY_FULFILLED, REJECTED -> false;
        };
    }
}
