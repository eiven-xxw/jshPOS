package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageView;
import com.jingshanghui.pos.catalog.application.service.CatalogPackageService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.PublishPackage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/packages")
public class CatalogPackageController {

    private final CatalogPackageService service;

    @PostMapping
    @SaCheckPermission("catalog:package:publish")
    @Log(title = "商品价格数据包", businessType = BusinessType.INSERT)
    public R<PackageView> publish(@Valid @RequestBody PublishPackage request) {
        return R.ok(service.publish(request.storeId(), request.packageVersion(), request.previousVersion()));
    }

    @GetMapping("/latest")
    @SaCheckPermission("catalog:package:query")
    public R<PackageView> latest(@RequestParam Long storeId) {
        return R.ok(service.latest(storeId));
    }
}
