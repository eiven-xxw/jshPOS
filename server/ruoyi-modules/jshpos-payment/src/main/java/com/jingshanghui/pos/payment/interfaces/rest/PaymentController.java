package com.jingshanghui.pos.payment.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateAttempt;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateIntent;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CancelTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CollectTenderAllocation;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RecoverTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TenderAllocationInput;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderCollectResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.TenderPlanResult;
import com.jingshanghui.pos.payment.application.service.PaymentCoreService;
import com.jingshanghui.pos.payment.application.service.TenderPlanService;
import com.jingshanghui.pos.payment.interfaces.rest.dto.PaymentRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 支付意图与 attempt 的人机应用 API；没有 Provider 回调和网络入口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/payments")
public class PaymentController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final PaymentCoreService service;
    private final TenderPlanService tenderService;

    @PostMapping("/intents")
    @SaCheckPermission("payment:intent:create")
    @Log(title = "创建支付意图", businessType = BusinessType.INSERT)
    public R<PaymentResult> createIntent(@RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                         @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                         @Valid @RequestBody PaymentRequests.CreateIntent request) {
        return R.ok(service.createIntent(new CreateIntent(commandId, idempotencyKey, request.paymentId(),
            request.orderId(), parsePlatformId(request.storeId()), request.terminalId(), request.amountMinor(),
            request.currency(), request.occurredAt())));
    }

    @PostMapping("/intents/{paymentId}/attempts")
    @SaCheckPermission("payment:attempt:create")
    @Log(title = "创建支付尝试", businessType = BusinessType.INSERT)
    public R<AttemptResult> createAttempt(@PathVariable @Pattern(regexp = ULID) String paymentId,
                                          @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                          @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                          @Valid @RequestBody PaymentRequests.CreateAttempt request) {
        return R.ok(service.createAttempt(new CreateAttempt(commandId, idempotencyKey, request.attemptId(),
            paymentId, request.providerCode(), request.providerRequestNo(), request.occurredAt())));
    }

    @GetMapping("/intents/{paymentId}")
    @SaCheckPermission("payment:read")
    public R<PaymentView> find(@PathVariable @Pattern(regexp = ULID) String paymentId) {
        return R.ok(service.find(paymentId));
    }

    @PostMapping("/tender-plans")
    @SaCheckPermission("payment:tender:create")
    @Log(title = "冻结组合支付计划", businessType = BusinessType.INSERT)
    public R<TenderPlanResult> createTenderPlan(
        @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody PaymentRequests.CreateTenderPlan request) {
        var allocations = request.allocations().stream().map(item -> new TenderAllocationInput(
            item.allocationId(), item.sequenceNo(), item.tenderType(), item.amountMinor())).toList();
        return R.ok(tenderService.create(new CreateTenderPlan(commandId, idempotencyKey, request.planId(),
            request.orderId(), request.orderSnapshotSha256(), parsePlatformId(request.storeId()),
            request.terminalId(), request.receivableAmountMinor(), request.currency(), allocations,
            request.occurredAt())));
    }

    @GetMapping("/tender-plans/{planId}")
    @SaCheckPermission("payment:tender:read")
    public R<TenderPlanResult> findTenderPlan(@PathVariable @Pattern(regexp = ULID) String planId) {
        return R.ok(tenderService.find(planId));
    }

    @PostMapping("/tender-plans/{planId}/allocations/{allocationId}/collect")
    @SaCheckPermission("payment:tender:collect")
    @Log(title = "收取组合支付份额", businessType = BusinessType.UPDATE)
    public R<TenderCollectResult> collectTenderAllocation(
        @PathVariable @Pattern(regexp = ULID) String planId,
        @PathVariable @Pattern(regexp = ULID) String allocationId,
        @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody PaymentRequests.CollectTenderAllocation request) {
        TenderCollectResult result = tenderService.collect(new CollectTenderAllocation(commandId, idempotencyKey,
            planId, allocationId, request.tenderedMinor(), request.occurredAt()));
        if ("PAYMENT_EXTERNAL_BLOCKED".equals(result.outcome())) {
            throw new ServiceException("PAYMENT_EXTERNAL_BLOCKED: T2-PAY-002 未解阻，电子份额禁止执行", 409);
        }
        return R.ok(result);
    }

    @PostMapping("/tender-plans/{planId}/cancel")
    @SaCheckPermission("payment:tender:cancel")
    @Log(title = "取消组合支付计划", businessType = BusinessType.UPDATE)
    public R<TenderPlanResult> cancelTenderPlan(
        @PathVariable @Pattern(regexp = ULID) String planId,
        @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody PaymentRequests.TenderPlanAction request) {
        return R.ok(tenderService.cancel(new CancelTenderPlan(commandId, idempotencyKey, planId,
            request.reasonCode(), request.occurredAt())));
    }

    @PostMapping("/tender-plans/{planId}/recover")
    @SaCheckPermission("payment:tender:recover")
    @Log(title = "检查组合支付恢复", businessType = BusinessType.OTHER)
    public R<TenderPlanResult> recoverTenderPlan(
        @PathVariable @Pattern(regexp = ULID) String planId,
        @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
        @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
        @Valid @RequestBody PaymentRequests.TenderPlanAction request) {
        return R.ok(tenderService.recover(new RecoverTenderPlan(commandId, idempotencyKey, planId,
            request.reasonCode(), request.occurredAt())));
    }

    private Long parsePlatformId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ServiceException("PAY-INPUT-004: 平台 ID 超出 BIGINT 正数范围", 400);
        }
    }
}
