package com.jingshanghui.pos.transfer.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.*;
import com.jingshanghui.pos.transfer.application.model.TransferViews.TransferDetail;
import com.jingshanghui.pos.transfer.application.model.TransferViews.TransitReconciliation;
import com.jingshanghui.pos.transfer.application.service.TransferService;
import com.jingshanghui.pos.transfer.interfaces.rest.dto.TransferRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 调拨 API；任何请求均不接收 tenant_id、库存余额或成本金额。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/transfers")
public class TransferController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final TransferService service;

    @PostMapping
    @SaCheckPermission("transfer:order:create")
    @Log(title = "创建调拨", businessType = BusinessType.INSERT)
    public R<TransferDetail> create(@Valid @RequestBody TransferRequests.Create request) {
        return R.ok(service.create(new CreateTransfer(request.transferId(), positive(request.sourceStoreId()),
            request.sourceWarehouseId(), positive(request.destinationStoreId()), request.destinationWarehouseId(),
            request.lines().stream().map(line -> new CreateLine(line.transferLineId(), positive(line.skuId()),
                positive(line.unitId()), line.requestedQuantity())).toList(), request.reason(), request.correlationId())));
    }

    @PostMapping("/{transferId}/submit")
    @SaCheckPermission("transfer:order:submit")
    @Log(title = "提交调拨", businessType = BusinessType.UPDATE)
    public R<TransferDetail> submit(@PathVariable @Pattern(regexp = ULID) String transferId,
                                    @Valid @RequestBody TransferRequests.State request) {
        return R.ok(service.submit(state(transferId, request)));
    }

    @PostMapping("/{transferId}/approve")
    @SaCheckPermission("transfer:order:approve")
    @Log(title = "审批调拨", businessType = BusinessType.UPDATE)
    public R<TransferDetail> approve(@PathVariable @Pattern(regexp = ULID) String transferId,
                                     @Valid @RequestBody TransferRequests.State request) {
        return R.ok(service.approve(state(transferId, request)));
    }

    @PostMapping("/{transferId}/dispatch")
    @SaCheckPermission("transfer:dispatch:post")
    @Log(title = "调拨发出", businessType = BusinessType.UPDATE)
    public R<TransferDetail> dispatch(@PathVariable @Pattern(regexp = ULID) String transferId,
                                      @Valid @RequestBody TransferRequests.Dispatch request) {
        return R.ok(service.dispatch(new DispatchTransfer(transferId, request.dispatchId(), request.eventId(),
            request.expectedVersion(), request.correlationId())));
    }

    @PostMapping("/{transferId}/receipts")
    @SaCheckPermission("transfer:receipt:post")
    @Log(title = "调拨收货", businessType = BusinessType.UPDATE)
    public R<TransferDetail> receive(@PathVariable @Pattern(regexp = ULID) String transferId,
                                     @Valid @RequestBody TransferRequests.Receive request) {
        return R.ok(service.receive(new ReceiveTransfer(transferId, request.receiptId(), request.eventId(),
            request.expectedVersion(), request.finalReceipt(), request.lines().stream().map(line ->
            new ReceiveLine(line.receiptLineId(), line.transferLineId(), line.receivedQuantity())).toList(),
            request.correlationId())));
    }

    @PostMapping("/{transferId}/difference")
    @SaCheckPermission("transfer:difference:approve")
    @Log(title = "审批调拨差异", businessType = BusinessType.UPDATE)
    public R<TransferDetail> difference(@PathVariable @Pattern(regexp = ULID) String transferId,
                                        @Valid @RequestBody TransferRequests.Difference request) {
        return R.ok(service.resolveDifference(new ResolveDifference(transferId, request.commandId(),
            request.expectedVersion(), request.lines().stream().map(line -> new DifferenceLine(
                line.transferLineId(), line.differenceQuantity(), line.differenceReason())).toList(),
            request.reason(), request.correlationId())));
    }

    @PostMapping("/{transferId}/cancel")
    @SaCheckPermission("transfer:order:cancel")
    @Log(title = "取消调拨", businessType = BusinessType.UPDATE)
    public R<TransferDetail> cancel(@PathVariable @Pattern(regexp = ULID) String transferId,
                                    @Valid @RequestBody TransferRequests.State request) {
        return R.ok(service.cancel(state(transferId, request)));
    }

    @GetMapping("/{transferId}")
    @SaCheckPermission("transfer:order:read")
    public R<TransferDetail> detail(@PathVariable @Pattern(regexp = ULID) String transferId) {
        return R.ok(service.detail(transferId));
    }

    @GetMapping("/{transferId}/transit-reconciliation")
    @SaCheckPermission("transfer:order:read")
    public R<TransitReconciliation> transitReconciliation(
        @PathVariable @Pattern(regexp = ULID) String transferId) {
        return R.ok(service.reconcileTransit(transferId));
    }

    private StateCommand state(String transferId, TransferRequests.State value) {
        return new StateCommand(transferId, value.commandId(), value.expectedVersion(), value.reason(),
            value.correlationId());
    }

    private Long positive(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ServiceException("TRF-INPUT-004: BIGINT 标识非法", 400);
        }
    }
}
