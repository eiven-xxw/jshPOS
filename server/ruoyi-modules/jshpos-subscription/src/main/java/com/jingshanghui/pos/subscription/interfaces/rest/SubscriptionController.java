package com.jingshanghui.pos.subscription.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.subscription.application.model.SubscriptionModels.*;
import com.jingshanghui.pos.subscription.application.service.SubscriptionApplicationService;
import com.jingshanghui.pos.subscription.interfaces.rest.dto.SubscriptionRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 订阅生命周期 REST 协议边界；不包含计费、扣款或 Provider 接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/subscriptions")
public class SubscriptionController {
    private static final String SAFE="^[A-Za-z0-9._:-]{1,64}$";
    private static final String ULID="^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String TENANT="^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$";
    private final SubscriptionApplicationService service;

    @PostMapping("/tenants/{targetTenantId}") @SaCheckPermission("subscription:create")
    @Log(title="创建商户订阅",businessType=BusinessType.INSERT)
    public R<SubscriptionDetail> create(@PathVariable @Pattern(regexp=TENANT) String targetTenantId,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.Create request){return R.ok(service.create(new CreateSubscription(targetTenantId,
            request.contractRef(),request.externalOrderRef(),request.startsAt(),request.endsAt(),request.graceEndsAt(),
            request.businessTimeZone(),request.degradationPolicyVersion(),key,correlation)));}

    @GetMapping("/{id}") @SaCheckPermission("subscription:read")
    public R<SubscriptionDetail> detail(@PathVariable @Pattern(regexp=ULID) String id){return R.ok(service.detail(id));}

    @GetMapping("/current") @SaCheckPermission("subscription:self:read")
    public R<SubscriptionDetail> current(){return R.ok(service.current());}

    @PostMapping("/{id}/activate") @SaCheckPermission("subscription:activate")
    @Log(title="激活订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> activate(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation){return R.ok(service.activate(command(id,"首次激活",key,correlation)));}

    @PostMapping("/{id}/renew") @SaCheckPermission("subscription:renew")
    @Log(title="续期订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> renew(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.NewTerm request){return R.ok(service.renew(term(id,request,key,correlation)));}

    @PostMapping("/{id}/suspend") @SaCheckPermission("subscription:suspend")
    @Log(title="暂停订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> suspend(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.Reason request){return R.ok(service.suspend(command(id,request.reason(),key,correlation)));}

    @PostMapping("/{id}/restore") @SaCheckPermission("subscription:restore")
    @Log(title="恢复订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> restore(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.NewTerm request){return R.ok(service.restore(term(id,request,key,correlation)));}

    @PostMapping("/{id}/request-termination") @SaCheckPermission("subscription:terminate")
    @Log(title="申请终止订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> requestTermination(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.Reason request){return R.ok(service.requestTermination(command(id,request.reason(),key,correlation)));}

    @PostMapping("/{id}/terminate") @SaCheckPermission("subscription:terminate")
    @Log(title="逻辑终止订阅",businessType=BusinessType.UPDATE)
    public R<SubscriptionDetail> terminate(@PathVariable @Pattern(regexp=ULID) String id,
        @RequestHeader("Idempotency-Key") @Size(min=8,max=64) @Pattern(regexp=SAFE) String key,
        @RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String correlation,
        @Valid @RequestBody SubscriptionRequests.Reason request){return R.ok(service.terminate(command(id,request.reason(),key,correlation)));}

    @PostMapping("/jobs/expiry-scan") @SaCheckPermission("subscription:job:run")
    @Log(title="执行订阅到期扫描",businessType=BusinessType.UPDATE)
    public R<ScanResult> scan(@RequestHeader("X-Correlation-ID") @Pattern(regexp=SAFE) String runner){return R.ok(service.runExpiryScan(runner));}

    private SubscriptionCommand command(String id,String reason,String key,String correlation){return new SubscriptionCommand(id,reason,key,correlation);}
    private NewTermCommand term(String id,SubscriptionRequests.NewTerm r,String key,String correlation){return new NewTermCommand(id,r.contractRef(),r.externalOrderRef(),r.startsAt(),r.endsAt(),r.graceEndsAt(),r.businessTimeZone(),key,correlation);}
}
