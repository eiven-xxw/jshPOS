package com.jingshanghui.pos.inventory.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Approve;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Create;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.RecordCount;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Review;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Submit;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Detail;
import com.jingshanghui.pos.inventory.application.service.StocktakeService;
import com.jingshanghui.pos.inventory.interfaces.rest.dto.StocktakeRequests;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 动态盘点命令 API；审批后的库存效果只能走库存 Owner 内部端口。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/stocktakes")
public class StocktakeController {

    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final StocktakeService service;

    @PostMapping
    @SaCheckPermission("inventory:stocktake:create")
    @Log(title = "创建动态盘点", businessType = BusinessType.INSERT)
    public R<Detail> create(@Valid @RequestBody StocktakeRequests.Create request) {
        return R.ok(service.create(new Create(request.stocktakeId(), request.warehouseId(), request.skuIds(),
            request.blindCount(), request.recountThreshold(), request.correlationId())));
    }

    @PostMapping("/{stocktakeId}/lines/{lineId}/counts")
    @SaCheckPermission("inventory:stocktake:count")
    @Log(title = "录入盘点计数", businessType = BusinessType.INSERT)
    public R<Detail> count(@PathVariable @Pattern(regexp = ULID) String stocktakeId,
                           @PathVariable @Pattern(regexp = ULID) String lineId,
                           @Valid @RequestBody StocktakeRequests.Count request) {
        return R.ok(service.recordCount(new RecordCount(stocktakeId, lineId, request.countId(),
            request.countedQuantity(), request.deviceId(), request.reason(), request.correlationId())));
    }

    @PostMapping("/{stocktakeId}/submit")
    @SaCheckPermission("inventory:stocktake:submit")
    @Log(title = "提交盘点复核", businessType = BusinessType.UPDATE)
    public R<Detail> submit(@PathVariable @Pattern(regexp = ULID) String stocktakeId,
                            @Valid @RequestBody StocktakeRequests.Correlated request) {
        return R.ok(service.submit(new Submit(stocktakeId, request.correlationId())));
    }

    @PostMapping("/{stocktakeId}/review")
    @SaCheckPermission("inventory:stocktake:review")
    @Log(title = "复核盘点差异", businessType = BusinessType.UPDATE)
    public R<Detail> review(@PathVariable @Pattern(regexp = ULID) String stocktakeId,
                            @Valid @RequestBody StocktakeRequests.Review request) {
        return R.ok(service.review(new Review(stocktakeId, request.decision(), request.reason(),
            request.correlationId())));
    }

    @PostMapping("/{stocktakeId}/approve")
    @SaCheckPermission("inventory:stocktake:approve")
    @Log(title = "审批盘点并生成库存流水", businessType = BusinessType.UPDATE)
    public R<Detail> approve(@PathVariable @Pattern(regexp = ULID) String stocktakeId,
                             @Valid @RequestBody StocktakeRequests.Approve request) {
        return R.ok(service.approve(new Approve(stocktakeId, request.eventId(), request.correlationId())));
    }

    @GetMapping("/{stocktakeId}")
    @SaCheckPermission("inventory:stocktake:read")
    public R<Detail> detail(@PathVariable @Pattern(regexp = ULID) String stocktakeId) {
        return R.ok(service.detail(stocktakeId));
    }
}
