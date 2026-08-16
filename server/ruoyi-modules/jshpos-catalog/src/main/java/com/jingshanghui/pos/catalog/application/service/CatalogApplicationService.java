package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.domain.CatalogRules;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class CatalogApplicationService {

    private final CatalogMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final DomainAuditService auditService;
    private final ObjectMapper objectMapper;
    private final CatalogOutboxService outboxService;

    @Transactional
    public DefinitionView createCategory(Long parentId, String code, String name, int sortNo) {
        String tenantId = tenantContext.requireTenantId();
        if (parentId != null) {
            requireActive(mapper.findCategory(tenantId, parentId), "CAT-PRD-020: 父分类不存在或已停用");
        }
        Long id = IdWorker.getId();
        DefinitionView result = new DefinitionView(id, CatalogRules.requireCode(code, "CAT-PRD-021"),
            CatalogRules.requireName(name), "ACTIVE");
        mapper.insertCategory(tenantId, id, parentId, result.code(), result.name(), sortNo);
        auditService.append("CATEGORY_CREATED", "CATEGORY", id, null, result, Map.of("code", result.code()));
        return result;
    }

    @Transactional
    public DefinitionView createBrand(String code, String name) {
        String tenantId = tenantContext.requireTenantId();
        Long id = IdWorker.getId();
        DefinitionView result = new DefinitionView(id, CatalogRules.requireCode(code, "CAT-PRD-022"),
            CatalogRules.requireName(name), "ACTIVE");
        mapper.insertBrand(tenantId, id, result.code(), result.name());
        auditService.append("BRAND_CREATED", "BRAND", id, null, result, Map.of("code", result.code()));
        return result;
    }

    @Transactional
    public DefinitionView createUnit(String code, String name, int decimalScale) {
        String tenantId = tenantContext.requireTenantId();
        if (decimalScale < 0 || decimalScale > 6) {
            throw new ServiceException("CAT-PRD-023: 单位精度必须为 0..6", 400);
        }
        Long id = IdWorker.getId();
        DefinitionView result = new DefinitionView(id, CatalogRules.requireCode(code, "CAT-PRD-024"),
            CatalogRules.requireName(name), "ACTIVE");
        mapper.insertUnit(tenantId, id, result.code(), result.name(), decimalScale);
        auditService.append("UNIT_CREATED", "UNIT", id, null, result,
            Map.of("code", result.code(), "decimalScale", decimalScale));
        return result;
    }

    @Transactional
    public ProductView createProduct(CreateProduct command) {
        String tenantId = tenantContext.requireTenantId();
        requireActive(mapper.findCategory(tenantId, command.categoryId()), "CAT-PRD-025: 分类不存在或已停用");
        if (command.brandId() != null) {
            requireActive(mapper.findBrand(tenantId, command.brandId()), "CAT-PRD-026: 品牌不存在或已停用");
        }
        requireUnitShape(command.units());
        Long spuId = IdWorker.getId();
        Long skuId = IdWorker.getId();
        String spuCode = CatalogRules.requireCode(command.spuCode(), "CAT-PRD-027");
        String skuCode = CatalogRules.requireCode(command.skuCode(), "CAT-PRD-028");
        String name = CatalogRules.requireName(command.name());
        String productType = CatalogRules.requireProductType(command.productType());
        String attributesJson = canonicalAttributes(command.attributes());
        mapper.insertSpu(tenantId, spuId, spuCode, name, command.categoryId(), command.brandId(), attributesJson);
        mapper.insertSku(tenantId, skuId, spuId, skuCode, name, productType, attributesJson);
        Set<String> allBarcodes = new HashSet<>();
        for (UnitInput unit : command.units()) {
            requireActive(mapper.findUnit(tenantId, unit.unitId()), "CAT-PRD-029: 单位不存在或已停用");
            CatalogRules.UnitRatio ratio = CatalogRules.requireRatio(unit.ratioNumerator(), unit.ratioDenominator());
            Long skuUnitId = IdWorker.getId();
            mapper.insertSkuUnit(tenantId, skuUnitId, skuId, unit.unitId(), ratio.numerator(), ratio.denominator(), unit.primary());
            for (String rawBarcode : unit.barcodes()) {
                String barcode = CatalogRules.requireBarcode(rawBarcode);
                if (!allBarcodes.add(barcode)) {
                    throw new ServiceException("CAT-PRD-030: 同一商品条码重复", 409);
                }
                mapper.insertBarcode(tenantId, IdWorker.getId(), skuId, skuUnitId, barcode,
                    "WEIGHT".equals(productType) ? "WEIGHT" : "STANDARD");
            }
        }
        ProductView result = mapper.findProduct(tenantId, skuId);
        auditService.append("PRODUCT_CREATED", "SKU", skuId, null, result,
            Map.of("spuCode", spuCode, "skuCode", skuCode, "unitCount", command.units().size()));
        outboxService.append(tenantId, "product.changed.v1", "SKU", skuId, 1,
            "{\"changeType\":\"CREATED\",\"skuId\":" + skuId + ",\"status\":\"DRAFT\"}");
        return result;
    }

    @Transactional(readOnly = true)
    public List<ProductView> listProducts(String status, int limit) {
        String tenantId = tenantContext.requireTenantId();
        if (status != null && !CatalogRules.PRODUCT_STATES.contains(status)) {
            throw new ServiceException("CAT-PRD-031: 商品状态筛选无效", 400);
        }
        return mapper.listProducts(tenantId, status, Math.max(1, Math.min(limit, 500)));
    }

    @Transactional
    public ProductView changeState(Long skuId, String requestedState, int version) {
        String tenantId = tenantContext.requireTenantId();
        ProductView before = requireProduct(tenantId, skuId);
        String next = CatalogRules.transitionState(before.status(), requestedState);
        if (mapper.updateProductState(tenantId, skuId, next, version) != 1) {
            throw new ServiceException("CAT-PRD-032: 商品版本冲突", 409);
        }
        ProductView after = requireProduct(tenantId, skuId);
        auditService.append("PRODUCT_STATE_CHANGED", "SKU", skuId, before, after,
            Map.of("from", before.status(), "to", after.status()));
        outboxService.append(tenantId, "product.changed.v1", "SKU", skuId, after.version().longValue(),
            "{\"changeType\":\"UPDATED\",\"skuId\":" + skuId + ",\"status\":\"" + after.status() + "\"}");
        return after;
    }

    private ProductView requireProduct(String tenantId, Long skuId) {
        ProductView product = mapper.findProduct(tenantId, skuId);
        if (product == null) {
            throw new ServiceException("CAT-PRD-033: 商品不存在或不可见", 404);
        }
        return product;
    }

    private void requireUnitShape(List<UnitInput> units) {
        if (units == null || units.isEmpty() || units.size() > 50) {
            throw new ServiceException("CAT-PRD-034: 商品必须有 1..50 个单位", 400);
        }
        long primaryCount = units.stream().filter(UnitInput::primary).count();
        if (primaryCount != 1) {
            throw new ServiceException("CAT-PRD-035: 商品必须且只能有一个主单位", 400);
        }
        Set<Long> unitIds = new HashSet<>();
        for (UnitInput unit : units) {
            if (unit.unitId() == null || !unitIds.add(unit.unitId())) {
                throw new ServiceException("CAT-PRD-036: 商品单位不能为空或重复", 409);
            }
            CatalogRules.UnitRatio ratio = CatalogRules.requireRatio(unit.ratioNumerator(), unit.ratioDenominator());
            if (unit.primary() && (ratio.numerator() != 1 || ratio.denominator() != 1)) {
                throw new ServiceException("CAT-PRD-037: 主单位换算必须为 1/1", 400);
            }
        }
    }

    private void requireActive(DefinitionView value, String error) {
        if (value == null || !"ACTIVE".equals(value.status())) {
            throw new ServiceException(error, 404);
        }
    }

    private String canonicalAttributes(Map<String, Object> attributes) {
        TreeMap<String, Object> canonical = new TreeMap<>(attributes == null ? Map.of() : attributes);
        canonical.putIfAbsent("schemaVersion", "1.0");
        if (canonical.size() > 50) {
            throw new ServiceException("CAT-PRD-039: 扩展属性最多允许 50 个键", 400);
        }
        for (String key : canonical.keySet()) {
            if (key == null || !key.matches("^[A-Za-z][A-Za-z0-9_.-]{0,63}$")) {
                throw new ServiceException("CAT-PRD-040: 扩展属性键格式无效", 400);
            }
            String normalized = key.toLowerCase();
            if (normalized.contains("password") || normalized.contains("secret") || normalized.contains("token")) {
                throw new ServiceException("CAT-PRD-041: 扩展属性禁止保存敏感凭据", 400);
            }
        }
        Object schemaVersion = canonical.get("schemaVersion");
        if (!(schemaVersion instanceof String version) || !version.matches("^[1-9][0-9]*\\.[0-9]+$")) {
            throw new ServiceException("CAT-PRD-042: 扩展属性 schemaVersion 无效", 400);
        }
        try {
            String json = objectMapper.writeValueAsString(canonical);
            if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16 * 1024) {
                throw new ServiceException("CAT-PRD-043: 扩展属性超过 16 KiB", 400);
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("CAT-PRD-038: 扩展属性无法 canonical 序列化", 400);
        }
    }

    public record CreateProduct(String spuCode, String skuCode, String name, Long categoryId, Long brandId,
                                String productType, Map<String, Object> attributes, List<UnitInput> units) {
        public CreateProduct {
            units = units == null ? List.of() : List.copyOf(units);
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }

    public record UnitInput(Long unitId, Long ratioNumerator, Long ratioDenominator,
                            boolean primary, List<String> barcodes) {
        public UnitInput {
            barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
        }
    }
}
