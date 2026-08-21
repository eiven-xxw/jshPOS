package com.jingshanghui.pos.returns.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.ApproveReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.RequestLine;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.RequestReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PreviewReturn;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnPreview;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.application.service.ReturnOrchestrationService;
import com.jingshanghui.pos.returns.interfaces.rest.dto.ReturnRequests;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 原单退货退款公开 API；跨 Owner Saga 推进与 Owner 回执不得暴露为公网操作接口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/returns")
public class ReturnController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ReturnOrchestrationService service;

    @PostMapping("/preview")
    @SaCheckPermission("return:request:read")
    public R<ReturnPreview> preview(@Valid @RequestBody ReturnRequests.Preview request) {
        var lines = request.lines() == null ? java.util.List.<ReturnRequests.Line>of() : request.lines();
        return R.ok(service.preview(new PreviewReturn(request.orderQuery(), lines.stream()
            .map(line -> new RequestLine(line.orderLineId(), line.quantity().toPlainString())).toList())));
    }

    @PostMapping
    @SaCheckPermission("return:request:create")
    @Log(title = "申请原单退货退款", businessType = BusinessType.INSERT)
    public R<ReturnView> create(@Valid @RequestBody ReturnRequests.Create request) {
        return R.ok(service.request(new RequestReturn(request.commandId(), request.idempotencyKey(),
            request.returnId(), request.orderId(), positive(request.storeId()), request.terminalId(),
            request.refundShiftId(), request.warehouseId(), request.businessDate(), request.settlementKind(),
            request.paymentId(), request.reasonCode(), request.lines().stream().map(line ->
            new RequestLine(line.orderLineId(), line.quantity().toPlainString())).toList(),
            request.correlationId(), request.occurredAt())));
    }

    @PostMapping("/{returnId}/approve")
    @SaCheckPermission("return:request:approve")
    @Log(title = "审批原单退货退款", businessType = BusinessType.UPDATE)
    public R<ReturnView> approve(@PathVariable @Pattern(regexp = ULID) String returnId,
                                 @Valid @RequestBody ReturnRequests.Approve request) {
        return R.ok(service.approve(new ApproveReturn(request.commandId(), returnId, request.reasonCode(),
            request.correlationId(), request.occurredAt())));
    }

    @GetMapping("/{returnId}")
    @SaCheckPermission("return:request:read")
    public R<ReturnView> detail(@PathVariable @Pattern(regexp = ULID) String returnId) {
        return R.ok(service.find(returnId));
    }

    private Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ServiceException("RET-INPUT-004: BIGINT 标识非法", 400);
        }
    }
}
