package com.jingshanghui.pos.procurement.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.*;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.*;
import com.jingshanghui.pos.procurement.application.service.ProcurementService;
import com.jingshanghui.pos.procurement.interfaces.rest.dto.ProcurementRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 采购 API；确认收货和原收货退货的库存效果由服务端 Owner 端口生成。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/procurement")
public class ProcurementController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final ProcurementService service;

    @PostMapping("/suppliers")
    @SaCheckPermission("procurement:supplier:create")
    @Log(title = "创建供应商", businessType = BusinessType.INSERT)
    public R<Supplier> createSupplier(@Valid @RequestBody ProcurementRequests.SupplierCreate request) {
        return R.ok(service.createSupplier(new CreateSupplier(request.supplierId(), request.code(), request.name(),
            request.correlationId())));
    }

    @PostMapping("/suppliers/{supplierId}/state")
    @SaCheckPermission("procurement:supplier:state")
    @Log(title = "变更供应商状态", businessType = BusinessType.UPDATE)
    public R<Supplier> supplierState(@PathVariable @Pattern(regexp = ULID) String supplierId,
                                     @Valid @RequestBody ProcurementRequests.SupplierState request) {
        return R.ok(service.changeSupplierState(new ChangeSupplierState(supplierId, request.state(),
            request.reason(), request.correlationId())));
    }

    @PostMapping("/orders")
    @SaCheckPermission("procurement:order:create")
    @Log(title = "创建采购单", businessType = BusinessType.INSERT)
    public R<OrderDetail> createOrder(@Valid @RequestBody ProcurementRequests.OrderCreate request) {
        return R.ok(service.createOrder(new CreateOrder(request.orderId(), request.supplierId(),
            parsePositive(request.storeId(), "storeId"), request.warehouseId(), request.expectedDate(),
            request.overReceiptToleranceBps(), request.lines().stream().map(line ->
                new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.OrderLine(
                line.orderLineId(), parsePositive(line.skuId(), "skuId"), parsePositive(line.unitId(), "unitId"),
                line.orderedQuantity(), parseNonNegative(line.unitPriceMinor(), "unitPriceMinor"),
                line.taxRateBps())).toList(), request.correlationId())));
    }

    @PostMapping("/orders/{orderId}/approve")
    @SaCheckPermission("procurement:order:approve")
    @Log(title = "审批采购单", businessType = BusinessType.UPDATE)
    public R<OrderDetail> approveOrder(@PathVariable @Pattern(regexp = ULID) String orderId,
                                       @Valid @RequestBody ProcurementRequests.Correlated request) {
        return R.ok(service.approveOrder(new ApproveOrder(orderId, request.correlationId())));
    }

    @PostMapping("/orders/{orderId}/submit")
    @SaCheckPermission("procurement:order:submit")
    @Log(title = "提交采购单审批", businessType = BusinessType.UPDATE)
    public R<OrderDetail> submitOrder(@PathVariable @Pattern(regexp = ULID) String orderId,
                                      @Valid @RequestBody ProcurementRequests.Correlated request) {
        return R.ok(service.submitOrder(new SubmitOrder(orderId, request.correlationId())));
    }

    @PostMapping("/orders/{orderId}/close")
    @SaCheckPermission("procurement:order:close")
    @Log(title = "关闭采购单", businessType = BusinessType.UPDATE)
    public R<OrderDetail> closeOrder(@PathVariable @Pattern(regexp = ULID) String orderId,
                                     @Valid @RequestBody ProcurementRequests.Reasoned request) {
        return R.ok(service.closeOrder(new CloseOrder(orderId, request.reason(), request.correlationId())));
    }

    @PostMapping("/orders/{orderId}/receipts")
    @SaCheckPermission("procurement:receipt:create")
    @Log(title = "创建采购收货草稿", businessType = BusinessType.INSERT)
    public R<ReceiptDetail> createReceipt(@PathVariable @Pattern(regexp = ULID) String orderId,
                                          @Valid @RequestBody ProcurementRequests.ReceiptCreate request) {
        return R.ok(service.createReceipt(new CreateReceipt(request.receiptId(), orderId,
            request.lines().stream().map(line ->
                new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLine(
                line.receiptLineId(), line.orderLineId(),
                line.receivedQuantity())).toList(), request.correlationId())));
    }

    @PostMapping("/receipts/{receiptId}/confirm")
    @SaCheckPermission("procurement:receipt:confirm")
    @Log(title = "确认采购收货", businessType = BusinessType.UPDATE)
    public R<ReceiptDetail> confirmReceipt(@PathVariable @Pattern(regexp = ULID) String receiptId,
                                           @Valid @RequestBody ProcurementRequests.Confirm request) {
        return R.ok(service.confirmReceipt(new ConfirmReceipt(receiptId, request.eventId(),
            request.correlationId(), request.lotSplits() == null ? List.of() : request.lotSplits().stream()
                .map(split -> new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLotSplit(
                    split.receiptLineId(), split.baseQuantity(), split.supplierLotCode(), split.internalLotCode(),
                    split.productionDate(), split.receivedDate(), split.explicitExpiryDate())).toList())));
    }

    @PostMapping("/receipts/{receiptId}/returns")
    @SaCheckPermission("procurement:return:create")
    @Log(title = "创建原收货退货草稿", businessType = BusinessType.INSERT)
    public R<ReturnHead> createReturn(@PathVariable @Pattern(regexp = ULID) String receiptId,
                                      @Valid @RequestBody ProcurementRequests.ReturnCreate request) {
        return R.ok(service.createReturn(new CreateReturn(request.purchaseReturnId(), receiptId,
            request.lines().stream().map(line ->
                new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReturnLine(line.returnLineId(),
                line.receiptLineId(), line.returnQuantity())).toList(), request.reason(),
            request.correlationId())));
    }

    @PostMapping("/returns/{purchaseReturnId}/submit")
    @SaCheckPermission("procurement:return:submit")
    @Log(title = "提交采购退货审批", businessType = BusinessType.UPDATE)
    public R<ReturnHead> submitReturn(@PathVariable @Pattern(regexp = ULID) String purchaseReturnId,
                                      @Valid @RequestBody ProcurementRequests.Correlated request) {
        return R.ok(service.submitReturn(new SubmitReturn(purchaseReturnId, request.correlationId())));
    }

    @PostMapping("/returns/{purchaseReturnId}/approve")
    @SaCheckPermission("procurement:return:approve")
    @Log(title = "审批原收货退货", businessType = BusinessType.UPDATE)
    public R<ReturnHead> approveReturn(@PathVariable @Pattern(regexp = ULID) String purchaseReturnId,
                                       @Valid @RequestBody ProcurementRequests.ReturnApprove request) {
        return R.ok(service.approveReturn(new ApproveReturn(purchaseReturnId, request.eventId(),
            request.correlationId(), request.lotSplits().stream().map(split ->
                new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReturnLotSplit(
                    split.returnLineId(), split.lotId(), split.baseQuantity())).toList())));
    }

    @GetMapping("/orders/{orderId}")
    @SaCheckPermission("procurement:order:read")
    public R<OrderDetail> order(@PathVariable @Pattern(regexp = ULID) String orderId) {
        return R.ok(service.orderDetail(orderId));
    }

    @GetMapping("/receipts/{receiptId}")
    @SaCheckPermission("procurement:receipt:read")
    public R<ReceiptDetail> receipt(@PathVariable @Pattern(regexp = ULID) String receiptId) {
        return R.ok(service.receiptDetail(receiptId));
    }

    private Long parsePositive(String value, String field) {
        long parsed = parse(value, field);
        if (parsed <= 0) throw new ServiceException("PUR-INPUT-003: " + field + " 必须为正数", 400);
        return parsed;
    }

    private long parseNonNegative(String value, String field) {
        long parsed = parse(value, field);
        if (parsed < 0) throw new ServiceException("PUR-INPUT-004: " + field + " 不可为负", 400);
        return parsed;
    }

    private long parse(String value, String field) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new ServiceException("PUR-INPUT-005: " + field + " 超出 BIGINT 范围", 400);
        }
    }
}
