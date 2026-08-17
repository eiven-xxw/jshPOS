package com.jingshanghui.pos.member.application.model;

import java.time.LocalDateTime;

/** 对外只暴露最小化、已脱敏的会员视图。 */
public final class MemberViews {
    public record MemberView(String memberId, String state, String displayName, int version,
                             LocalDateTime createdAt) { }
    public record IdentityView(String identityId, String memberId, String identityType, String maskedValue,
                               String state, int keyVersion, LocalDateTime createdAt) { }
    public record ResolvedMemberView(MemberView member, IdentityView matchedIdentity) { }
    public record ConsentView(String consentId, String memberId, String purposeCode, String policyVersion,
                              String state, String evidenceSha256, LocalDateTime occurredAt) { }
    public record PrivacyRequestView(String requestId, String memberId, String requestType, String state,
                                     int version, LocalDateTime submittedAt, LocalDateTime completedAt) { }
    public record MemberLinkView(String linkId, String sourceMemberId, String targetMemberId, String action,
                                 LocalDateTime occurredAt) { }
    private MemberViews() { }
}
