package com.jingshanghui.pos.payment.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateAttempt;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateIntent;
import com.jingshanghui.pos.payment.application.model.PaymentViews.AttemptResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.PaymentView;
import com.jingshanghui.pos.payment.application.service.PaymentCoreService;
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
