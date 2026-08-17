package com.jingshanghui.pos.member.application.model;

/** 会员 Owner 的命令契约；tenant_id 永远不由客户端提供。 */
public final class MemberCommands {
    public record CreateMember(String commandId, String memberId, String identityId, String identityType,
                               String identityValue, String correlationId) { }
    public record IdentityCommand(String commandId, String memberId, String identityId, String identityType,
                                  String identityValue, String reason, String correlationId) { }
    public record RevokeIdentity(String commandId, String memberId, String identityId, String reason,
                                 String correlationId) { }
    public record ConsentCommand(String commandId, String memberId, String consentId, String purposeCode,
                                 String policyVersion, String state, String evidenceSha256,
                                 String correlationId) { }
    public record PrivacyCommand(String commandId, String memberId, String requestId, String requestType,
                                 String reason, String correlationId) { }
    public record PrivacyTransition(String commandId, String requestId, String toState, int expectedVersion,
                                    String reason, String correlationId) { }
    public record MergeCommand(String commandId, String sourceMemberId, String targetMemberId, String linkId,
                               String reason, String correlationId) { }
    public record SplitCommand(String commandId, String sourceMemberId, String targetMemberId, String linkId,
                               String reason, String correlationId) { }
    private MemberCommands() { }
}
