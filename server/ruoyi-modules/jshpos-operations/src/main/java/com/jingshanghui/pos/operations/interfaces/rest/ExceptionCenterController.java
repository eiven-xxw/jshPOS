package com.jingshanghui.pos.operations.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.operations.application.model.ExceptionModels.*;
import com.jingshanghui.pos.operations.application.service.ExceptionCenterService;
import com.jingshanghui.pos.operations.interfaces.rest.dto.ExceptionCenterRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 统一异常中心协议边界；Controller 不计算来源、严重级别、资金、库存或 Owner 结果。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/operations/exceptions")
public class ExceptionCenterController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SAFE = "^[A-Za-z0-9._:-]+$";
    private final ExceptionCenterService service;

    @GetMapping
    @SaCheckPermission("operations:exception:read")
    public R<List<CaseRecord>> list(@RequestParam @Positive Long storeId,
                                    @RequestParam(required=false) String state,
                                    @RequestParam(required=false) String severity,
                                    @RequestParam(defaultValue="50") @Min(1) @Max(100) int limit) {
        return R.ok(service.list(storeId,state,severity,limit));
    }

    @GetMapping("/{caseId}")
    @SaCheckPermission("operations:exception:read")
    public R<CaseDetail> detail(@PathVariable @Pattern(regexp=ULID) String caseId) { return R.ok(service.detail(caseId)); }

    @PostMapping("/scan")
    @SaCheckPermission("operations:exception:scan")
    @Log(title="扫描Owner异常",businessType=BusinessType.OTHER)
    public R<List<CaseRecord>> scan(@Valid @RequestBody ExceptionCenterRequests.Scan request,
                                    @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                    @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.scan(new ScanCommand(request.storeId(),request.businessDate(),key,correlation)));
    }

    @PostMapping("/{caseId}/claim")
    @SaCheckPermission("operations:exception:claim")
    @Log(title="认领异常案件",businessType=BusinessType.UPDATE)
    public R<CaseDetail> claim(@PathVariable @Pattern(regexp=ULID) String caseId,
                               @Valid @RequestBody ExceptionCenterRequests.Claim request,
                               @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                               @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.claim(new ClaimCommand(caseId,request.leaseMinutes(),key,correlation)));
    }

    @PostMapping("/{caseId}/start")
    @SaCheckPermission("operations:exception:operate")
    public R<CaseDetail> start(@PathVariable @Pattern(regexp=ULID) String caseId,
                               @Valid @RequestBody ExceptionCenterRequests.Reason request,
                               @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                               @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.start(new CaseCommand(caseId,request.reason(),key,correlation)));
    }

    @PostMapping("/{caseId}/transfer")
    @SaCheckPermission("operations:exception:operate")
    public R<CaseDetail> transfer(@PathVariable @Pattern(regexp=ULID) String caseId,
                                  @Valid @RequestBody ExceptionCenterRequests.Transfer request,
                                  @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                  @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.transfer(new TransferCommand(caseId,request.assigneeUserId(),request.leaseMinutes(),request.reason(),key,correlation)));
    }

    @PostMapping("/{caseId}/plan")
    @SaCheckPermission("operations:exception:operate")
    public R<CaseDetail> plan(@PathVariable @Pattern(regexp=ULID) String caseId,
                              @Valid @RequestBody ExceptionCenterRequests.Plan request,
                              @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                              @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.plan(new PlanCommand(caseId,request.actionCode(),request.planSummary(),key,correlation)));
    }

    @PostMapping("/{caseId}/repair")
    @SaCheckPermission("operations:exception:repair")
    @Log(title="执行Owner修复",businessType=BusinessType.UPDATE)
    public R<CaseDetail> repair(@PathVariable @Pattern(regexp=ULID) String caseId,
                                @Valid @RequestBody ExceptionCenterRequests.Repair request,
                                @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.repair(new RepairCommand(caseId,request.actionCode(),key,correlation)));
    }

    @PostMapping("/{caseId}/review")
    @SaCheckPermission("operations:exception:review")
    public R<CaseDetail> review(@PathVariable @Pattern(regexp=ULID) String caseId,
                                @Valid @RequestBody ExceptionCenterRequests.Reason request,
                                @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.review(new CaseCommand(caseId,request.reason(),key,correlation)));
    }

    @PostMapping("/{caseId}/close")
    @SaCheckPermission("operations:exception:close")
    public R<CaseDetail> close(@PathVariable @Pattern(regexp=ULID) String caseId,
                               @Valid @RequestBody ExceptionCenterRequests.Reason request,
                               @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                               @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.close(new CaseCommand(caseId,request.reason(),key,correlation)));
    }

    @PostMapping("/{caseId}/reopen")
    @SaCheckPermission("operations:exception:close")
    public R<CaseDetail> reopen(@PathVariable @Pattern(regexp=ULID) String caseId,
                                @Valid @RequestBody ExceptionCenterRequests.Reason request,
                                @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
                                @RequestHeader("X-Correlation-ID") @Size(min=1,max=64) @Pattern(regexp=SAFE) String correlation) {
        return R.ok(service.reopen(new CaseCommand(caseId,request.reason(),key,correlation)));
    }
}
