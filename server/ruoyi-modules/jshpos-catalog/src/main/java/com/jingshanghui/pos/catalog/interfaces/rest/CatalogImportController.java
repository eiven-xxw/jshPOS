package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportBatchView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportPreflightView;
import com.jingshanghui.pos.catalog.application.service.CatalogImportService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.ImportPreflight;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/imports")
public class CatalogImportController {

    private final CatalogImportService service;

    @PostMapping("/preflight")
    @SaCheckPermission("catalog:import:preflight")
    @Log(title = "商品导入预检", businessType = BusinessType.IMPORT)
    public R<ImportPreflightView> preflight(@Valid @RequestBody ImportPreflight request) {
        return R.ok(service.preflight(request.idempotencyKey(), request.rows().stream().map(row -> row.toCommand()).toList()));
    }

    @PostMapping("/{batchId}/publish")
    @SaCheckPermission("catalog:import:publish")
    @Log(title = "商品导入发布", businessType = BusinessType.IMPORT)
    public R<ImportBatchView> publish(@PathVariable Long batchId) {
        return R.ok(service.publish(batchId));
    }

    @PostMapping("/{batchId}/rollback")
    @SaCheckPermission("catalog:import:publish")
    @Log(title = "商品导入安全回退", businessType = BusinessType.UPDATE)
    public R<ImportBatchView> rollback(@PathVariable Long batchId) {
        return R.ok(service.rollback(batchId));
    }
}
