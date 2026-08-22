package com.jingshanghui.pos.onboarding.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.onboarding.application.model.OnboardingModels.*;
import com.jingshanghui.pos.onboarding.application.service.OnboardingService;
import com.jingshanghui.pos.onboarding.interfaces.rest.dto.OnboardingRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 门店复制、模板应用和开店检查 REST 边界。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/onboarding/plans")
public class OnboardingController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SAFE = "^[A-Za-z0-9._:-]+$";
    private final OnboardingService service;

    @PostMapping
    @SaCheckPermission("onboarding:plan:create")
    @Log(title = "创建门店开通计划", businessType = BusinessType.INSERT)
    public R<PlanDetail> create(@RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                                @Pattern(regexp = SAFE) String idempotencyKey,
                                @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                                @Pattern(regexp = SAFE) String correlationId,
                                @Valid @RequestBody OnboardingRequests.Create request) {
        return R.ok(service.create(new CreatePlan(request.sourceStoreId(), request.targetStoreId(),
            request.templateId(), request.templateVersionId(), idempotencyKey, correlationId)));
    }

    @GetMapping("/{planId}")
    @SaCheckPermission("onboarding:plan:read")
    public R<PlanDetail> detail(@PathVariable @Pattern(regexp = ULID) String planId) {
        return R.ok(service.detail(planId));
    }

    @PostMapping("/{planId}/preflight")
    @SaCheckPermission("onboarding:plan:preflight")
    @Log(title = "预检门店开通计划", businessType = BusinessType.UPDATE)
    public R<PlanDetail> preflight(@PathVariable @Pattern(regexp = ULID) String planId,
                                   @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                                   @Pattern(regexp = SAFE) String idempotencyKey,
                                   @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                                   @Pattern(regexp = SAFE) String correlationId) {
        return R.ok(service.preflight(command(planId, idempotencyKey, correlationId)));
    }

    @PostMapping("/{planId}/approve")
    @SaCheckPermission("onboarding:plan:approve")
    @Log(title = "审批门店开通计划", businessType = BusinessType.UPDATE)
    public R<PlanDetail> approve(@PathVariable @Pattern(regexp = ULID) String planId,
                                 @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                                 @Pattern(regexp = SAFE) String idempotencyKey,
                                 @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                                 @Pattern(regexp = SAFE) String correlationId,
                                 @Valid @RequestBody OnboardingRequests.Reason request) {
        return R.ok(service.approve(reason(planId, request.reason(), idempotencyKey, correlationId)));
    }

    @PostMapping("/{planId}/apply")
    @SaCheckPermission("onboarding:plan:apply")
    @Log(title = "应用门店开通计划", businessType = BusinessType.UPDATE)
    public R<PlanDetail> apply(@PathVariable @Pattern(regexp = ULID) String planId,
                               @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                               @Pattern(regexp = SAFE) String idempotencyKey,
                               @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                               @Pattern(regexp = SAFE) String correlationId) {
        return R.ok(service.apply(command(planId, idempotencyKey, correlationId)));
    }

    @PostMapping("/{planId}/checks")
    @SaCheckPermission("onboarding:plan:check")
    @Log(title = "执行门店开店检查", businessType = BusinessType.UPDATE)
    public R<PlanDetail> checks(@PathVariable @Pattern(regexp = ULID) String planId,
                                @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                                @Pattern(regexp = SAFE) String idempotencyKey,
                                @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                                @Pattern(regexp = SAFE) String correlationId) {
        return R.ok(service.checks(command(planId, idempotencyKey, correlationId)));
    }

    @PostMapping("/{planId}/open")
    @SaCheckPermission("onboarding:plan:open")
    @Log(title = "确认门店开店里程碑", businessType = BusinessType.UPDATE)
    public R<PlanDetail> open(@PathVariable @Pattern(regexp = ULID) String planId,
                              @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                              @Pattern(regexp = SAFE) String idempotencyKey,
                              @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                              @Pattern(regexp = SAFE) String correlationId,
                              @Valid @RequestBody OnboardingRequests.Reason request) {
        return R.ok(service.open(reason(planId, request.reason(), idempotencyKey, correlationId)));
    }

    @PostMapping("/{planId}/cancel")
    @SaCheckPermission("onboarding:plan:cancel")
    @Log(title = "取消门店开通计划", businessType = BusinessType.UPDATE)
    public R<PlanDetail> cancel(@PathVariable @Pattern(regexp = ULID) String planId,
                                @RequestHeader("Idempotency-Key") @Size(min = 8, max = 64)
                                @Pattern(regexp = SAFE) String idempotencyKey,
                                @RequestHeader("X-Correlation-ID") @Size(min = 1, max = 64)
                                @Pattern(regexp = SAFE) String correlationId,
                                @Valid @RequestBody OnboardingRequests.Reason request) {
        return R.ok(service.cancel(reason(planId, request.reason(), idempotencyKey, correlationId)));
    }

    private PlanCommand command(String planId, String key, String correlation) {
        return new PlanCommand(planId, key, correlation);
    }

    private ReasonCommand reason(String planId, String reason, String key, String correlation) {
        return new ReasonCommand(planId, reason, key, correlation);
    }
}
