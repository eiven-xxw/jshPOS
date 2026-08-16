package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceBookView;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.application.service.PriceBookService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.AddPriceItem;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreatePriceBook;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/price-books")
public class PriceBookController {

    private final PriceBookService service;

    @PostMapping
    @SaCheckPermission("catalog:price:manage")
    @Log(title = "价格簿", businessType = BusinessType.INSERT)
    public R<PriceBookView> create(@Valid @RequestBody CreatePriceBook request) {
        return R.ok(service.create(request.code(), request.name(), request.versionNo(), request.scopeType(), request.storeId()));
    }

    @PostMapping("/{bookId}/items")
    @SaCheckPermission("catalog:price:manage")
    @Log(title = "价格项", businessType = BusinessType.INSERT)
    public R<Long> addItem(@PathVariable Long bookId, @Valid @RequestBody AddPriceItem request) {
        return R.ok(service.addItem(bookId, request.skuId(), request.unitId(), request.amountMinor(),
            request.effectiveFrom(), request.effectiveTo()));
    }

    @PostMapping("/{bookId}/publish")
    @SaCheckPermission("catalog:price:publish")
    @Log(title = "价格发布", businessType = BusinessType.UPDATE)
    public R<PriceBookView> publish(@PathVariable Long bookId) {
        return R.ok(service.publish(bookId));
    }

    @PostMapping("/{bookId}/retire")
    @SaCheckPermission("catalog:price:publish")
    @Log(title = "价格版本安全停用", businessType = BusinessType.UPDATE)
    public R<PriceBookView> retire(@PathVariable Long bookId) {
        return R.ok(service.retire(bookId));
    }

    @GetMapping("/resolve")
    @SaCheckPermission("catalog:price:query")
    public R<ResolvedPrice> resolve(
        @RequestParam Long skuId,
        @RequestParam Long unitId,
        @RequestParam Long storeId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at
    ) {
        return R.ok(service.resolve(skuId, unitId, storeId, at));
    }
}
