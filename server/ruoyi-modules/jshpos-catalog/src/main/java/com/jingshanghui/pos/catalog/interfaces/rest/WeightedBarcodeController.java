package com.jingshanghui.pos.catalog.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodePreview;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodeTemplateView;
import com.jingshanghui.pos.catalog.application.service.WeightedBarcodeService;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.ChangeWeightedBarcodeState;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.CreateWeightedBarcodeTemplate;
import com.jingshanghui.pos.catalog.interfaces.rest.dto.CatalogRequests.PreviewWeightedBarcode;
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
import org.springframework.web.bind.annotation.RestController;

/** T2-PRD-005 秤码模板协议适配层，不承载解析、价格或成交算法。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/catalog/weighted-barcode-templates")
public class WeightedBarcodeController {

    private final WeightedBarcodeService service;

    @PostMapping
    @SaCheckPermission("catalog:weighted-barcode:manage")
    @Log(title = "秤码模板", businessType = BusinessType.INSERT)
    public R<WeightedBarcodeTemplateView> create(@Valid @RequestBody CreateWeightedBarcodeTemplate request) {
        return R.ok(service.create(new WeightedBarcodeService.CreateTemplate(request.templateCode(),
            request.versionNo(), request.scopeType(), request.storeId(), request.barcodeKind(), request.prefixValue(),
            request.skuStartPos(), request.skuLength(), request.valueStartPos(), request.valueLength(),
            request.valueScale(), request.priorityNo(), request.effectiveFrom(), request.effectiveTo())));
    }

    @GetMapping("/{templateId}")
    @SaCheckPermission("catalog:weighted-barcode:query")
    public R<WeightedBarcodeTemplateView> get(@PathVariable Long templateId) {
        return R.ok(service.get(templateId));
    }

    @PutMapping("/{templateId}/publish")
    @SaCheckPermission("catalog:weighted-barcode:publish")
    @Log(title = "秤码模板发布", businessType = BusinessType.UPDATE)
    public R<WeightedBarcodeTemplateView> publish(@PathVariable Long templateId,
                                                   @Valid @RequestBody ChangeWeightedBarcodeState request) {
        return R.ok(service.publish(templateId, request.expectedVersion()));
    }

    @PutMapping("/{templateId}/retire")
    @SaCheckPermission("catalog:weighted-barcode:manage")
    @Log(title = "秤码模板退役", businessType = BusinessType.UPDATE)
    public R<WeightedBarcodeTemplateView> retire(@PathVariable Long templateId,
                                                  @Valid @RequestBody ChangeWeightedBarcodeState request) {
        return R.ok(service.retire(templateId, request.expectedVersion()));
    }

    @PostMapping("/preview")
    @SaCheckPermission("catalog:weighted-barcode:preview")
    public R<WeightedBarcodePreview> preview(@Valid @RequestBody PreviewWeightedBarcode request) {
        return R.ok(service.preview(request.storeId(), request.rawBarcode(), request.occurredAt()));
    }
}
