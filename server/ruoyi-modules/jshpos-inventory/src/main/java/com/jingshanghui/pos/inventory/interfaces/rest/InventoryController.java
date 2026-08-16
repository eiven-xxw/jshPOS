package com.jingshanghui.pos.inventory.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplyReturn;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.PublishPolicy;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.RebuildBalance;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.PolicyView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.RebuildResult;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.interfaces.rest.dto.InventoryRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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

import java.util.List;

/** 库存命令 API；业务行始终由服务端 Owner 端口解析。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final InventoryLedgerService service;

    @PostMapping("/sales/{orderId}/apply")
    @SaCheckPermission("inventory:movement:apply")
    @Log(title = "应用销售库存出库", businessType = BusinessType.INSERT)
    public R<ApplyResult> applySale(@PathVariable @Pattern(regexp = ULID) String orderId,
                                    @Valid @RequestBody InventoryRequests.SourceCommand request) {
        return R.ok(service.applySale(new ApplySale(request.eventId(), orderId, request.warehouseId(),
            request.correlationId())));
    }

    @PostMapping("/returns/{refundId}/apply")
    @SaCheckPermission("inventory:movement:apply")
    @Log(title = "应用退货库存入库", businessType = BusinessType.INSERT)
    public R<ApplyResult> applyReturn(@PathVariable @Pattern(regexp = ULID) String refundId,
                                      @Valid @RequestBody InventoryRequests.SourceCommand request) {
        return R.ok(service.applyReturn(new ApplyReturn(request.eventId(), refundId, request.warehouseId(),
            request.correlationId())));
    }

    @PostMapping("/policies")
    @SaCheckPermission("inventory:policy:publish")
    @Log(title = "发布库存策略版本", businessType = BusinessType.INSERT)
    public R<PolicyView> publishPolicy(@Valid @RequestBody InventoryRequests.PublishPolicy request) {
        return R.ok(service.publishPolicy(new PublishPolicy(request.policyVersionId(), parsePlatformId(request.storeId()),
            request.warehouseId(), request.negativeStockMode(), request.effectiveFrom(), request.correlationId())));
    }

    @GetMapping("/balances/{warehouseId}/{skuId}")
    @SaCheckPermission("inventory:balance:read")
    public R<BalanceView> findBalance(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                      @PathVariable @Min(1) Long skuId) {
        return R.ok(service.findBalance(warehouseId, skuId));
    }

    @GetMapping("/ledgers/{warehouseId}/{skuId}")
    @SaCheckPermission("inventory:ledger:read")
    public R<List<LedgerView>> findLedger(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                          @PathVariable @Min(1) Long skuId) {
        return R.ok(service.findLedger(warehouseId, skuId));
    }

    @PostMapping("/balances/{warehouseId}/{skuId}/rebuild")
    @SaCheckPermission("inventory:rebuild")
    @Log(title = "重建库存余额投影", businessType = BusinessType.UPDATE)
    public R<RebuildResult> rebuild(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                    @PathVariable @Min(1) Long skuId,
                                    @Valid @RequestBody InventoryRequests.Rebuild request) {
        return R.ok(service.rebuild(new RebuildBalance(warehouseId, skuId, request.correlationId())));
    }

    private Long parsePlatformId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ServiceException("INV-INPUT-003: 平台 ID 超出 BIGINT 正数范围", 400);
        }
    }
}
