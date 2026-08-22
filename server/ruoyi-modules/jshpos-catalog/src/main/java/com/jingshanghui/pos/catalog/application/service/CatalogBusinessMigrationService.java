package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort.MigrationBindingView;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Catalog Owner 的开业商品迁移适配器。
 *
 * <p>绑定表与商品 DRAFT 在同一事务提交，避免 Migration Owner 在崩溃恢复时直接查询或写入 cat_*。</p>
 */
@Service
@RequiredArgsConstructor
public class CatalogBusinessMigrationService implements BusinessMigrationCatalogPort {
    private final CatalogMigrationPersistencePort persistence;
    private final CatalogApplicationService catalog;
    private final TrustedTenantContext tenantContext;

    @Override
    @Transactional
    public ProductMigrationResult importDraftProduct(ProductMigrationCommand command) {
        requireCommand(command);
        String tenantId = tenantContext.requireTenantId();
        MigrationBindingView existing = persistence.findMigrationBinding(tenantId, command.batchId(), command.rowId());
        if (existing != null) return replay(existing, command.rowSha256());
        if (persistence.findProductByCode(tenantId, command.skuCode()) != null) {
            throw new ServiceException("DMT-CATALOG-001: SKU 编码已存在且不属于当前迁移行", 409);
        }
        DefinitionView category = definition(persistence.findCategoryByCode(tenantId, command.categoryCode()),
            command.categoryName(), () -> catalog.createCategory(null, command.categoryCode(), command.categoryName(), 0),
            "分类");
        DefinitionView brand = null;
        if (command.brandCode() != null && !command.brandCode().isBlank()) {
            brand = definition(persistence.findBrandByCode(tenantId, command.brandCode()), command.brandName(),
                () -> catalog.createBrand(command.brandCode(), command.brandName()), "品牌");
        }
        DefinitionView unit = definition(persistence.findUnitByCode(tenantId, command.unitCode()), command.unitName(),
            () -> catalog.createUnit(command.unitCode(), command.unitName(), command.decimalScale()), "单位");
        ProductView product = catalog.createProduct(new CatalogApplicationService.CreateProduct(command.spuCode(),
            command.skuCode(), command.name(), category.id(), brand == null ? null : brand.id(),
            command.productType(), Map.of("schemaVersion", "1.0", "migrationBatchId", command.batchId()),
            List.of(new CatalogApplicationService.UnitInput(unit.id(), command.ratioNumerator(),
                command.ratioDenominator(), true, command.barcodes()))));
        persistence.insertMigrationBinding(tenantId, command.batchId(), command.rowId(), command.rowSha256(),
            product.skuId(), unit.id(), command.skuCode());
        return new ProductMigrationResult(product.skuId(), unit.id(), product.skuCode(), product.status(),
            command.rowSha256(), false);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductMigrationResult requireProduct(String batchId, String skuCode) {
        String tenantId = tenantContext.requireTenantId();
        MigrationBindingView value = persistence.findMigrationBindingBySkuCode(tenantId, batchId, skuCode);
        if (value == null) throw new ServiceException("DMT-CATALOG-002: 迁移批次商品不存在", 404);
        return result(value, false);
    }

    @Override
    @Transactional
    public int activateBatch(String batchId, String correlationId) {
        String tenantId = tenantContext.requireTenantId();
        List<MigrationBindingView> bindings = persistence.listMigrationBindings(tenantId, batchId);
        if (bindings.isEmpty()) return 0;
        int changed = 0;
        for (MigrationBindingView binding : bindings) {
            ProductView product = persistence.findProduct(tenantId, binding.skuId());
            if (product == null) throw new ServiceException("DMT-CATALOG-003: 迁移商品绑定已损坏", 409);
            if ("DRAFT".equals(product.status())) {
                catalog.changeState(product.skuId(), "ACTIVE", product.version());
                changed++;
            } else if (!"ACTIVE".equals(product.status())) {
                throw new ServiceException("DMT-CATALOG-004: 迁移商品状态不允许激活", 409);
            }
        }
        return changed;
    }

    private ProductMigrationResult replay(MigrationBindingView existing, String hash) {
        if (!existing.rowSha256().equals(hash)) {
            throw new ServiceException("DMT-CATALOG-IDEM: 相同迁移行对应不同内容", 409);
        }
        return result(existing, true);
    }

    private ProductMigrationResult result(MigrationBindingView value, boolean replay) {
        ProductView product = persistence.findProduct(tenantContext.requireTenantId(), value.skuId());
        if (product == null) throw new ServiceException("DMT-CATALOG-003: 迁移商品绑定已损坏", 409);
        return new ProductMigrationResult(value.skuId(), value.baseUnitId(), value.skuCode(), product.status(),
            value.rowSha256(), replay);
    }

    private DefinitionView definition(DefinitionView existing, String expectedName,
                                      java.util.function.Supplier<DefinitionView> creator, String type) {
        if (existing == null) return creator.get();
        if (!existing.name().equals(expectedName) || !"ACTIVE".equals(existing.status())) {
            throw new ServiceException("DMT-CATALOG-005: " + type + "编码存在但名称或状态冲突", 409);
        }
        return existing;
    }

    private void requireCommand(ProductMigrationCommand value) {
        if (value == null || value.batchId() == null || value.rowId() == null
            || value.rowSha256() == null || !value.rowSha256().matches("^[a-f0-9]{64}$")
            || value.correlationId() == null || value.correlationId().isBlank()) {
            throw new ServiceException("DMT-CATALOG-INPUT: 商品迁移命令非法", 400);
        }
    }
}
