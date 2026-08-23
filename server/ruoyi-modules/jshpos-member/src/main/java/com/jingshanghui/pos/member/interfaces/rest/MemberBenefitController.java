package com.jingshanghui.pos.member.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.member.application.model.BenefitCommands.*;
import com.jingshanghui.pos.member.application.model.BenefitViews.*;
import com.jingshanghui.pos.member.application.service.MemberBenefitService;
import com.jingshanghui.pos.member.interfaces.rest.dto.BenefitRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** T2-MEM-003 权益 API；Controller 只做协议、权限与 DTO 转换。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MemberBenefitController {
    private final MemberBenefitService service;

    @PostMapping("/member-benefit-policies")
    @SaCheckPermission("member:benefit:create")
    @Log(title="创建会员权益草稿",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public R<PolicyVersionView> create(@Valid @RequestBody BenefitRequests.Create r) {
        return R.ok(service.createDraft(new CreateDraft(r.commandId(),r.policyId(),r.versionId(),r.policyCode(),
            r.displayName(),r.levelRules().stream().map(v->new LevelRule(v.levelCode(),v.memberPriceEligible(),
                v.stackingAllowed())).toList(),r.storeIds(),r.correlationId())));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/validate")
    @SaCheckPermission("member:benefit:validate")
    public R<PolicyVersionView> validate(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                         @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                         @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.validate(action(r,policyId,versionId)));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/approve")
    @SaCheckPermission("member:benefit:approve")
    @Log(title="批准会员权益版本",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public R<PolicyVersionView> approve(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                        @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                        @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.approve(action(r,policyId,versionId)));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/publish")
    @SaCheckPermission("member:benefit:publish")
    @Log(title="发布会员权益版本",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public R<PolicyVersionView> publish(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                        @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                        @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.publish(action(r,policyId,versionId)));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/pause")
    @SaCheckPermission("member:benefit:pause")
    public R<PolicyVersionView> pause(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                      @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                      @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.pause(action(r,policyId,versionId)));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/resume")
    @SaCheckPermission("member:benefit:publish")
    public R<PolicyVersionView> resume(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                       @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                       @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.resume(action(r,policyId,versionId)));
    }

    @PostMapping("/member-benefit-policies/{policyId}/versions/{versionId}/revoke")
    @SaCheckPermission("member:benefit:revoke")
    @Log(title="撤回会员权益版本",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public R<PolicyVersionView> revoke(@PathVariable @Pattern(regexp=BenefitRequests.ULID) String policyId,
                                       @PathVariable @Pattern(regexp=BenefitRequests.ULID) String versionId,
                                       @Valid @RequestBody BenefitRequests.Action r) {
        return R.ok(service.revoke(action(r,policyId,versionId)));
    }

    @PostMapping("/member-entitlements/issue")
    @SaCheckPermission("pos:member-benefit:quote")
    @Log(title="发行会员权益快照",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<EntitlementSnapshotView> issue(@Valid @RequestBody BenefitRequests.Issue r) {
        return R.ok(service.issue(new IssueEntitlement(r.commandId(),r.snapshotId(),r.memberId(),r.storeId(),
            r.quoteAt(),r.correlationId())));
    }

    private VersionAction action(BenefitRequests.Action r,String policyId,String versionId) {
        return new VersionAction(r.commandId(),policyId,versionId,r.contentSha256(),r.effectiveAt(),r.expiresAt(),
            r.reasonCode(),r.reason(),r.correlationId());
    }
}
