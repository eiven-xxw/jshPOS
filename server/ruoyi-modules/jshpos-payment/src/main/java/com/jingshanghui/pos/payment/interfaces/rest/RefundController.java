package com.jingshanghui.pos.payment.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.ApproveRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateRefund;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.RefundLine;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundResult;
import com.jingshanghui.pos.payment.application.model.PaymentViews.RefundView;
import com.jingshanghui.pos.payment.application.service.RefundService;
import com.jingshanghui.pos.payment.interfaces.rest.dto.PaymentRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
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

import java.util.List;

/** 原单退款申请与独立审批 API；Provider 观察只走内部端口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/refunds")
public class RefundController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final RefundService service;

    @PostMapping
    @SaCheckPermission("refund:create")
    @Log(title = "创建原单退款", businessType = BusinessType.INSERT)
    public R<RefundResult> create(@RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                  @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                  @Valid @RequestBody PaymentRequests.CreateRefund request) {
        List<RefundLine> lines = request.lines().stream()
            .map(line -> new RefundLine(line.orderLineId(), line.quantity(), line.amountMinor())).toList();
        return R.ok(service.create(new CreateRefund(commandId, idempotencyKey, request.refundId(),
            request.paymentId(), request.orderId(), request.amountMinor(), request.currency(),
            request.reasonCode(), lines, request.occurredAt())));
    }

    @PostMapping("/{refundId}/approve")
    @SaCheckPermission("refund:approve")
    @Log(title = "审批原单退款", businessType = BusinessType.UPDATE)
    public R<Void> approve(@PathVariable @Pattern(regexp = ULID) String refundId,
                           @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                           @Valid @RequestBody PaymentRequests.ApproveRefund request) {
        service.approve(new ApproveRefund(commandId, refundId, request.reasonCode(), request.occurredAt()));
        return R.ok();
    }

    @GetMapping("/{refundId}")
    @SaCheckPermission("refund:read")
    public R<RefundView> find(@PathVariable @Pattern(regexp = ULID) String refundId) {
        return R.ok(service.find(refundId));
    }
}
