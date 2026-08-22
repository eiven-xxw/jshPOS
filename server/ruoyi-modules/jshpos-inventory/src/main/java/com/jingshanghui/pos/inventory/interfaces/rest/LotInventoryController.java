package com.jingshanghui.pos.inventory.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.LotView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.RebuildCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.RebuildResult;
import com.jingshanghui.pos.inventory.application.service.LotInventoryService;
import com.jingshanghui.pos.inventory.application.service.LotDataPackageService;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.inventory.interfaces.rest.dto.LotInventoryRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;

/** 批次库存查询适配器；批次写入只对正式 Owner 进程内端口开放。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/inventory/lots")
public class LotInventoryController {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final LotInventoryService service;
    private final LotDataPackageService packageService;

    @GetMapping
    @SaCheckPermission("inventory:lot:read")
    public R<List<LotView>> lots(@RequestParam @Positive Long storeId,
                                 @RequestParam @Pattern(regexp = ULID) String warehouseId,
                                 @RequestParam @Positive Long skuId,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return R.ok(service.findLots(storeId, warehouseId, skuId, businessDate, status, limit));
    }

    @GetMapping("/alerts")
    @SaCheckPermission("inventory:lot:read")
    public R<List<LotView>> expiryAlerts(@RequestParam @Positive Long storeId,
                                         @RequestParam @Pattern(regexp = ULID) String warehouseId,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                         LocalDate businessDate,
                                         @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        return R.ok(service.findAlerts(storeId, warehouseId, businessDate, limit));
    }

    @GetMapping("/package")
    @SaCheckPermission("inventory:lot-package:read")
    public R<PackageArtifact> lotPackage(@RequestParam @Positive Long storeId,
                                         @RequestParam @Pattern(regexp = ULID) String warehouseId) {
        return R.ok(packageService.latest(storeId, warehouseId));
    }

    @PostMapping("/package")
    @SaCheckPermission("inventory:lot-package:publish")
    @Log(title = "发布批次效期数据包", businessType = BusinessType.INSERT)
    public R<PackageArtifact> publishLotPackage(@RequestParam @Positive Long storeId,
                                                @RequestParam @Pattern(regexp = ULID) String warehouseId,
                                                @RequestHeader("Idempotency-Key")
                                                @Pattern(regexp = ULID) String releaseId,
                                                @RequestHeader("X-Correlation-ID") String correlationId) {
        return R.ok(packageService.publish(storeId, warehouseId, releaseId, correlationId));
    }

    @PostMapping("/rebuild")
    @SaCheckPermission("inventory:lot:rebuild")
    @Log(title = "重建批次效期投影", businessType = BusinessType.UPDATE)
    public R<RebuildResult> rebuild(@Valid @RequestBody LotInventoryRequests.Rebuild request,
                                    @RequestHeader("X-Correlation-ID") String correlationId) {
        return R.ok(service.rebuild(new RebuildCommand(request.commandId(), request.storeId(),
            request.warehouseId(), request.skuId(), request.businessDate(), correlationId)));
    }
}
