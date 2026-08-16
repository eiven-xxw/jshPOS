package com.jingshanghui.pos.costing.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.costing.application.model.CostingCommands.PublishPolicy;
import com.jingshanghui.pos.costing.application.model.CostingCommands.RebuildBalance;
import com.jingshanghui.pos.costing.application.model.CostingViews.BalanceView;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerView;
import com.jingshanghui.pos.costing.application.model.CostingViews.PolicyView;
import com.jingshanghui.pos.costing.application.model.CostingViews.RebuildResult;
import com.jingshanghui.pos.costing.application.service.CostingService;
import com.jingshanghui.pos.costing.interfaces.rest.dto.CostingRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 成本策略、查询与受控重建 API；正式成本入账不暴露 REST 写入口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/inventory")
public class CostingController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final CostingService service;

    @PostMapping("/cost-policies")
    @SaCheckPermission("inventory:cost-policy:publish")
    @Log(title = "发布成本策略版本", businessType = BusinessType.INSERT)
    public R<PolicyView> publishPolicy(@Valid @RequestBody CostingRequests.PublishPolicy request) {
        return R.ok(service.publishPolicy(new PublishPolicy(request.policyVersionId(),
            parsePlatformId(request.storeId()), request.warehouseId(), request.effectiveFrom(),
            request.correlationId())));
    }

    @GetMapping("/cost-balances/{warehouseId}/{skuId}")
    @SaCheckPermission("inventory:cost-balance:read")
    public R<BalanceView> findBalance(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                      @PathVariable @Min(1) Long skuId) {
        return R.ok(service.findBalance(warehouseId, skuId));
    }

    @GetMapping("/cost-ledgers/{warehouseId}/{skuId}")
    @SaCheckPermission("inventory:cost-ledger:read")
    public R<List<LedgerView>> findLedger(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                          @PathVariable @Min(1) Long skuId,
                                          @RequestParam(defaultValue = "0") @Min(0) long afterSequence,
                                          @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return R.ok(service.findLedger(warehouseId, skuId, afterSequence, limit));
    }

    @PostMapping("/cost-balances/{warehouseId}/{skuId}/rebuild")
    @SaCheckPermission("inventory:cost-rebuild")
    @Log(title = "重建成本余额投影", businessType = BusinessType.UPDATE)
    public R<RebuildResult> rebuild(@PathVariable @Pattern(regexp = ULID) String warehouseId,
                                    @PathVariable @Min(1) Long skuId,
                                    @Valid @RequestBody CostingRequests.Rebuild request) {
        return R.ok(service.rebuild(new RebuildBalance(request.rebuildId(), warehouseId, skuId,
            request.correlationId())));
    }

    private Long parsePlatformId(String value) {
        try {
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException exception) {
            throw new ServiceException("CST-INPUT-003: 平台 ID 超出 BIGINT 正数范围", 400);
        }
    }
}
