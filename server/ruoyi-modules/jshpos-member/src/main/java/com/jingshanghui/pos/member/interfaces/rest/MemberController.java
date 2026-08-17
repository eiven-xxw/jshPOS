package com.jingshanghui.pos.member.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.member.application.model.MemberCommands.*;
import com.jingshanghui.pos.member.application.model.MemberViews.*;
import com.jingshanghui.pos.member.application.service.MemberProfileService;
import com.jingshanghui.pos.member.interfaces.rest.dto.MemberRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** Gate 5C 会员 API；Controller 只负责协议校验和命令转换。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MemberController {
    private final MemberProfileService service;

    @PostMapping("/members")
    @SaCheckPermission("member:profile:create")
    @Log(title="创建会员", businessType=BusinessType.INSERT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<MemberView> create(@Valid @RequestBody MemberRequests.Create request) {
        return R.ok(service.create(new CreateMember(request.commandId(), request.memberId(), request.identityId(),
            request.identityType(), request.identityValue(), request.correlationId())));
    }

    @PostMapping("/members/resolve")
    @SaCheckPermission("member:profile:read")
    public R<ResolvedMemberView> resolve(@Valid @RequestBody MemberRequests.Resolve request) {
        return R.ok(service.resolve(request.storeId(), request.identityType(), request.identityValue()));
    }

    @PostMapping("/members/{memberId}/identities")
    @SaCheckPermission("member:identity:bind")
    @Log(title="绑定会员身份", businessType=BusinessType.INSERT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<IdentityView> bind(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                @Valid @RequestBody MemberRequests.BindIdentity request) {
        return R.ok(service.bindIdentity(new IdentityCommand(request.commandId(), memberId, request.identityId(),
            request.identityType(), request.identityValue(), request.reason(), request.correlationId())));
    }

    @PostMapping("/members/{memberId}/identities/{identityId}/revoke")
    @SaCheckPermission("member:identity:revoke")
    @Log(title="撤销会员身份", businessType=BusinessType.UPDATE,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<IdentityView> revoke(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                  @PathVariable @Pattern(regexp=MemberRequests.ULID) String identityId,
                                  @Valid @RequestBody MemberRequests.Revoke request) {
        return R.ok(service.revokeIdentity(new RevokeIdentity(request.commandId(), memberId, identityId,
            request.reason(), request.correlationId())));
    }

    @PostMapping("/members/{memberId}/consents")
    @SaCheckPermission("member:consent:record")
    @Log(title="记录会员同意", businessType=BusinessType.INSERT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<ConsentView> consent(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                  @Valid @RequestBody MemberRequests.Consent request) {
        return R.ok(service.recordConsent(new ConsentCommand(request.commandId(), memberId, request.consentId(),
            request.purposeCode(), request.policyVersion(), request.state(), request.evidenceSha256(),
            request.correlationId())));
    }

    @PostMapping("/members/{memberId}/privacy-requests")
    @SaCheckPermission("member:privacy:request")
    @Log(title="提交隐私权利请求", businessType=BusinessType.INSERT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<PrivacyRequestView> privacy(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                         @Valid @RequestBody MemberRequests.Privacy request) {
        return R.ok(service.requestPrivacy(new PrivacyCommand(request.commandId(), memberId, request.requestId(),
            request.requestType(), request.reason(), request.correlationId())));
    }

    @PostMapping("/privacy-requests/{requestId}/transitions")
    @SaCheckPermission("member:privacy:process")
    @Log(title="处理隐私权利请求", businessType=BusinessType.UPDATE,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<PrivacyRequestView> transition(
        @PathVariable @Pattern(regexp=MemberRequests.ULID) String requestId,
        @Valid @RequestBody MemberRequests.PrivacyState request) {
        return R.ok(service.transitionPrivacy(new PrivacyTransition(request.commandId(), requestId,
            request.toState(), request.expectedVersion(), request.reason(), request.correlationId())));
    }

    @PostMapping("/members/merge")
    @SaCheckPermission("member:identity:merge")
    @Log(title="合并会员", businessType=BusinessType.UPDATE,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<MemberLinkView> merge(@Valid @RequestBody MemberRequests.Link request) {
        return R.ok(service.merge(new MergeCommand(request.commandId(), request.sourceMemberId(),
            request.targetMemberId(), request.linkId(), request.reason(), request.correlationId())));
    }

    @PostMapping("/members/split")
    @SaCheckPermission("member:identity:split")
    @Log(title="拆分会员", businessType=BusinessType.UPDATE,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<MemberLinkView> split(@Valid @RequestBody MemberRequests.Link request) {
        return R.ok(service.split(new SplitCommand(request.commandId(), request.sourceMemberId(),
            request.targetMemberId(), request.linkId(), request.reason(), request.correlationId())));
    }
}
