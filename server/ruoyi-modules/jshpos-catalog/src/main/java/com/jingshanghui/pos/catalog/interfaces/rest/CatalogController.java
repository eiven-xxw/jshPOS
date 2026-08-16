package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.service.CatalogApplicationService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.ChangeProductState;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreateCategory;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreateDefinition;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreateProduct;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreateUnit;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog")
public class CatalogController {

    private final CatalogApplicationService service;

    @PostMapping("/categories")
    @SaCheckPermission("catalog:definition:manage")
    @Log(title = "商品分类", businessType = BusinessType.INSERT)
    public R<DefinitionView> createCategory(@Valid @RequestBody CreateCategory request) {
        return R.ok(service.createCategory(request.parentId(), request.code(), request.name(), request.sortNo()));
    }

    @PostMapping("/brands")
    @SaCheckPermission("catalog:definition:manage")
    @Log(title = "商品品牌", businessType = BusinessType.INSERT)
    public R<DefinitionView> createBrand(@Valid @RequestBody CreateDefinition request) {
        return R.ok(service.createBrand(request.code(), request.name()));
    }

    @PostMapping("/units")
    @SaCheckPermission("catalog:definition:manage")
    @Log(title = "商品单位", businessType = BusinessType.INSERT)
    public R<DefinitionView> createUnit(@Valid @RequestBody CreateUnit request) {
        return R.ok(service.createUnit(request.code(), request.name(), request.decimalScale()));
    }

    @PostMapping("/products")
    @SaCheckPermission("catalog:product:manage")
    @Log(title = "商品", businessType = BusinessType.INSERT)
    public R<ProductView> createProduct(@Valid @RequestBody CreateProduct request) {
        List<CatalogApplicationService.UnitInput> units = request.units().stream()
            .map(unit -> new CatalogApplicationService.UnitInput(unit.unitId(), unit.ratioNumerator(),
                unit.ratioDenominator(), unit.primary(), unit.barcodes())).toList();
        return R.ok(service.createProduct(new CatalogApplicationService.CreateProduct(
            request.spuCode(), request.skuCode(), request.name(), request.categoryId(), request.brandId(),
            request.productType(), request.attributes(), units)));
    }

    @GetMapping("/products")
    @SaCheckPermission("catalog:product:query")
    public R<List<ProductView>> listProducts(@RequestParam(required = false) String status,
                                             @RequestParam(defaultValue = "100") int limit) {
        return R.ok(service.listProducts(status, limit));
    }

    @PutMapping("/products/{skuId}/state")
    @SaCheckPermission("catalog:product:manage")
    @Log(title = "商品状态", businessType = BusinessType.UPDATE)
    public R<ProductView> changeState(@PathVariable Long skuId, @Valid @RequestBody ChangeProductState request) {
        return R.ok(service.changeState(skuId, request.state(), request.version()));
    }
}
