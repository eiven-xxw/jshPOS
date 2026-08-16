package com.jingshanghui.pos.order.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.order.application.model.OrderCommands;
import com.jingshanghui.pos.order.application.model.OrderViews.CashOrderResult;
import com.jingshanghui.pos.order.application.service.CashOrderService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/pos")
public class CashOrderController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final CashOrderService service;

    @PostMapping("/cash-orders")
    @SaCheckPermission("pos:cash:collect")
    @Log(title = "现金订单", businessType = BusinessType.INSERT)
    public R<CashOrderResult> submit(@RequestHeader("X-Command-Id") @Pattern(regexp = ULID) String commandId,
                                     @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
                                     @Valid @RequestBody OrderRequests.CashOrder request) {
        List<OrderCommands.Line> lines = request.lines().stream().map(line -> new OrderCommands.Line(
            line.lineId(), line.lineNo(), parsePlatformId(line.skuId()), line.skuCode(), line.barcode(),
            line.productName(), parsePlatformId(line.unitId()), line.unitCode(), line.quantity(),
            line.unitPriceMinor(), line.grossAmountMinor(), line.payableAmountMinor(), line.priceSource())).toList();
        return R.ok(service.submit(new OrderCommands.CashOrder(commandId, idempotencyKey, request.orderId(),
            request.localOrderNo(), parsePlatformId(request.storeId()), request.terminalId(), request.shiftId(),
            request.cashierId(), request.businessDate(), request.storeTimezone(), request.catalogVersion(),
            request.priceVersion(), request.industryTemplateVersion(), request.grossAmountMinor(),
            request.receivableAmountMinor(), request.tenderedAmountMinor(), lines, request.occurredAt())));
    }

    @GetMapping("/orders/{orderId}")
    @SaCheckPermission("order:read")
    public R<OrderResponses.OrderDetails> find(@PathVariable @Pattern(regexp = ULID) String orderId) {
        return R.ok(OrderResponses.order(service.find(orderId)));
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
