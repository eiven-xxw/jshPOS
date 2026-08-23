package com.jingshanghui.pos.saas.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.saas.application.model.SaasModels.*;
import com.jingshanghui.pos.saas.application.service.SaasApplicationService;
import com.jingshanghui.pos.saas.infrastructure.persistence.entity.SaasPlanEntity;
import com.jingshanghui.pos.saas.interfaces.rest.dto.SaasRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 商户开户、套餐权益和商业租户生命周期 REST 协议边界。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/saas")
public class SaasOperationsController {
    private static final String SAFE = "^[A-Za-z0-9._:-]{1,64}$";
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String TENANT = "^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$";
    private final SaasApplicationService service;

    @PostMapping("/applications") @SaCheckPermission("saas:application:create")
    @Log(title="创建商户申请",businessType=BusinessType.INSERT)
    public R<ApplicationDetail> createApplication(@RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SaasRequests.ApplicationCreate request) {
        return R.ok(service.createApplication(new CreateApplication(request.applicationCode(),request.companyName(),request.industry(),request.planId(),key,correlation)));
    }

    @GetMapping("/applications/{id}") @SaCheckPermission("saas:application:read")
    public R<ApplicationDetail> detail(@PathVariable @Pattern(regexp=ULID) String id){return R.ok(service.detail(id));}

    @PostMapping("/applications/{id}/preflight") @SaCheckPermission("saas:application:preflight")
    @Log(title="预检商户申请",businessType=BusinessType.UPDATE)
    public R<ApplicationDetail> preflight(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation){return R.ok(service.preflight(command(id,null,key,correlation)));}

    @PostMapping("/applications/{id}/approve") @SaCheckPermission("saas:application:approve")
    @Log(title="审批商户申请",businessType=BusinessType.UPDATE)
    public R<ApplicationDetail> approve(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,@Valid @RequestBody SaasRequests.Reason r){return R.ok(service.approve(command(id,r.reason(),key,correlation)));}

    @PostMapping("/applications/{id}/provision") @SaCheckPermission("saas:application:provision")
    @Log(title="创建技术租户",businessType=BusinessType.INSERT,isSaveRequestData=false,isSaveResponseData=false)
    public R<ApplicationDetail> provision(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,@Valid @RequestBody SaasRequests.Provision r){
        return R.ok(service.provision(new ProvisionCommand(id,r.contactName(),r.contactPhone(),r.bootstrapUsername(),r.bootstrapPassword().toCharArray(),key,correlation)));
    }

    @PostMapping("/applications/{id}/initialize") @SaCheckPermission("saas:application:initialize")
    @Log(title="初始化租户",businessType=BusinessType.UPDATE)
    public R<ApplicationDetail> initialize(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation){return R.ok(service.initialize(command(id,null,key,correlation)));}

    @PostMapping("/applications/{id}/activate") @SaCheckPermission("saas:application:activate")
    @Log(title="激活租户",businessType=BusinessType.UPDATE)
    public R<ApplicationDetail> activate(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation){return R.ok(service.activate(command(id,null,key,correlation)));}

    @PostMapping("/plans") @SaCheckPermission("saas:plan:create") @Log(title="创建SaaS套餐",businessType=BusinessType.INSERT)
    public R<SaasPlanEntity> createPlan(@RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,@Valid @RequestBody SaasRequests.PlanCreate r){return R.ok(service.createPlan(new CreatePlan(r.planCode(),r.planName(),r.platformPackageId(),r.accountLimit(),key,correlation)));}

    @PostMapping("/plans/{planId}/versions") @SaCheckPermission("saas:entitlement:create") @Log(title="创建权益版本",businessType=BusinessType.INSERT)
    public R<EntitlementVersionRecord> createVersion(@PathVariable Long planId,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SaasRequests.VersionCreate r){return R.ok(service.createVersion(new CreateEntitlementVersion(planId,r.versionNo(),r.effectiveAt(),r.expiresAt(),r.items().stream().map(v->new EntitlementItemInput(v.featureCode(),v.enabled(),v.quotaLimit())).toList(),key,correlation)));}

    @PostMapping("/entitlements/{id}/{action}") @SaCheckPermission("saas:entitlement:publish") @Log(title="推进权益版本",businessType=BusinessType.UPDATE)
    public R<EntitlementVersionRecord> entitlement(@PathVariable @Pattern(regexp=ULID) String id,@PathVariable @Pattern(regexp="validate|approve|publish|activate") String action,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation){
        EntitlementCommand c=new EntitlementCommand(id,key,correlation); return R.ok(switch(action){case "validate"->service.validateVersion(c);case "approve"->service.approveVersion(c);case "publish"->service.publishVersion(c);default->service.activateVersion(c);});
    }

    @PostMapping("/tenants/{tenantId}/{action}") @SaCheckPermission("saas:tenant:lifecycle") @Log(title="变更租户商业生命周期",businessType=BusinessType.UPDATE)
    public R<TenantEntitlementRecord> lifecycle(@PathVariable @Pattern(regexp=TENANT) String tenantId,
        @PathVariable @Pattern(regexp="suspend|deactivate|restore|request-termination|terminate-logical") String action,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SaasRequests.Reason r){LifecycleCommand c=new LifecycleCommand(tenantId,r.reason(),key,correlation);return R.ok(switch(action){case "suspend"->service.suspend(c);case "deactivate"->service.deactivate(c);case "restore"->service.restore(c);case "request-termination"->service.requestTermination(c);default->service.terminateLogical(c);});}

    private ApplicationCommand command(String id,String reason,String key,String correlation){return new ApplicationCommand(id,reason,key,correlation);}
}
