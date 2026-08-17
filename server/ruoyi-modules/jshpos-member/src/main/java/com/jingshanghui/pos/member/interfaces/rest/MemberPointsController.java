package com.jingshanghui.pos.member.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.member.application.model.PointsCommands.*;
import com.jingshanghui.pos.member.application.model.PointsViews.*;
import com.jingshanghui.pos.member.application.service.MemberPointsService;
import com.jingshanghui.pos.member.interfaces.rest.dto.MemberRequests;
import com.jingshanghui.pos.member.interfaces.rest.dto.PointsRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;

/** Gate 5C 在线积分 API；不提供 POS 离线消费端点。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/members/{memberId}")
public class MemberPointsController {
    private final MemberPointsService service;

    @GetMapping("/points")
    @SaCheckPermission("member:points:read")
    public R<AccountView> account(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                  @RequestParam @jakarta.validation.constraints.Positive Long storeId) {
        return R.ok(service.account(memberId,storeId));
    }

    @PostMapping("/points/freezes")
    @SaCheckPermission("member:points:freeze")
    @Log(title="在线冻结会员积分",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<LedgerView> freeze(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                @Valid @RequestBody PointsRequests.Freeze request) {
        return R.ok(service.freeze(new Freeze(request.commandId(),request.ledgerId(),memberId,
            request.storeId(),new BigDecimal(request.amount()),request.policyVersion(),request.occurredAt(),request.correlationId())));
    }

    @PostMapping("/points/frozen-settlements")
    @SaCheckPermission("member:points:settle")
    @Log(title="在线结算会员冻结积分",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<LedgerView> settle(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                @Valid @RequestBody PointsRequests.Settle request) {
        return R.ok(service.settleFrozen(new FrozenSettlement(request.commandId(),request.ledgerId(),memberId,
            request.freezeLedgerId(),request.storeId(),new BigDecimal(request.amount()),request.action(),request.policyVersion(),
            request.occurredAt(),request.correlationId())));
    }

    @PostMapping("/points/adjustments")
    @SaCheckPermission("member:points:adjust")
    @Log(title="人工调整会员积分",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<LedgerView> adjust(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                @Valid @RequestBody PointsRequests.Adjust request) {
        return R.ok(service.adjust(new ManualAdjust(request.commandId(),request.ledgerId(),memberId,
            request.storeId(),new BigDecimal(request.signedAmount()),request.policyVersion(),request.reason(),
            request.approvalUserId(),request.approvalRef(),request.occurredAt(),
            request.correlationId())));
    }

    @PostMapping("/level-history")
    @SaCheckPermission("member:level:manage")
    @Log(title="变更会员等级",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<LevelView> level(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                              @Valid @RequestBody PointsRequests.Level request) {
        return R.ok(service.changeLevel(new ChangeLevel(request.commandId(),request.historyId(),memberId,
            request.storeId(),request.levelCode(),request.policyVersion(),request.reasonCode(),
            request.approvalUserId(),request.approvalRef(),request.effectiveAt(),
            request.correlationId())));
    }

    @PostMapping("/points/rebuild")
    @SaCheckPermission("member:points:rebuild")
    @Log(title="重建会员积分投影",businessType=BusinessType.UPDATE,isSaveRequestData=false,isSaveResponseData=false)
    public R<AccountView> rebuild(@PathVariable @Pattern(regexp=MemberRequests.ULID) String memberId,
                                  @RequestParam @jakarta.validation.constraints.Positive Long storeId) {
        return R.ok(service.rebuild(memberId,storeId));
    }
}
