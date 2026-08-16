package com.jingshanghui.pos.order.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.order.application.model.OrderCommands;
import com.jingshanghui.pos.order.application.service.ShiftService;
import com.jingshanghui.pos.order.interfaces.rest.dto.OrderRequests;
import com.jingshanghui.pos.order.interfaces.rest.dto.OrderResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/pos/shifts")
public class ShiftController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ShiftService service;

    @PostMapping
    @SaCheckPermission("pos:shift:open")
    @Log(title = "POS开班", businessType = BusinessType.INSERT)
    public R<OrderResponses.ShiftResult> open(@RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                             @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                             @Valid @RequestBody OrderRequests.OpenShift request) {
        return R.ok(OrderResponses.shift(service.open(new OrderCommands.OpenShift(commandId, idempotencyKey,
            parsePlatformId(request.storeId()), request.terminalId(), request.cashierId(), request.businessDate(),
            request.storeTimezone(), request.openingCashMinor(), request.configVersion(), request.occurredAt())), commandId));
    }

    @PostMapping("/{shiftId}/difference-approvals")
    @SaCheckPermission("pos:shift:approve-difference")
    @Log(title = "交班差异审批", businessType = BusinessType.GRANT)
    public R<OrderResponses.ApprovalResult> approve(@PathVariable @Pattern(regexp = ULID) String shiftId,
                                   @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                   @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                   @Valid @RequestBody OrderRequests.ApproveDifference request) {
        return R.ok(OrderResponses.approval(service.approveDifference(new OrderCommands.ApproveDifference(commandId, idempotencyKey,
            shiftId, request.actualCashMinor(), request.expectedVersion(), request.reasonCode(), request.reasonText(),
            request.occurredAt())), commandId));
    }

    @PostMapping("/{shiftId}/close")
    @SaCheckPermission("pos:shift:close")
    @Log(title = "POS交班", businessType = BusinessType.UPDATE)
    public R<OrderResponses.ShiftResult> close(@PathVariable @Pattern(regexp = ULID) String shiftId,
                              @RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                              @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                              @Valid @RequestBody OrderRequests.CloseShift request) {
        return R.ok(OrderResponses.shift(service.close(new OrderCommands.CloseShift(commandId, idempotencyKey, shiftId,
            request.actualCashMinor(), request.expectedVersion(), request.approvalId(), request.occurredAt())), commandId));
    }

    private Long parsePlatformId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("平台 ID 超出 BIGINT 正数范围", exception);
        }
    }
}
