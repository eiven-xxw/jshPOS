package com.jingshanghui.pos.catalog.infrastructure.persistence;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMigrationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** Catalog 开业迁移应用端口的 XML Mapper 基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class CatalogMigrationPersistenceAdapter implements CatalogMigrationPersistencePort {
    private final CatalogMigrationMapper mapper;

    @Override
    public DefinitionView findCategoryByCode(String tenantId, String code) {
        return mapper.findCategoryByCode(tenantId, code);
    }

    @Override
    public DefinitionView findBrandByCode(String tenantId, String code) {
        return mapper.findBrandByCode(tenantId, code);
    }

    @Override
    public DefinitionView findUnitByCode(String tenantId, String code) {
        return mapper.findUnitByCode(tenantId, code);
    }

    @Override
    public ProductView findProductByCode(String tenantId, String skuCode) {
        return mapper.findProductByCode(tenantId, skuCode);
    }

    @Override
    public ProductView findProduct(String tenantId, Long skuId) {
        return mapper.findProduct(tenantId, skuId);
    }

    @Override
    public int insertMigrationBinding(String tenantId, String batchId, String rowId, String rowSha256,
                                      Long skuId, Long baseUnitId, String skuCode) {
        return mapper.insertMigrationBinding(tenantId, batchId, rowId, rowSha256, skuId, baseUnitId, skuCode);
    }

    @Override
    public MigrationBindingView findMigrationBinding(String tenantId, String batchId, String rowId) {
        return mapper.findMigrationBinding(tenantId, batchId, rowId);
    }

    @Override
    public MigrationBindingView findMigrationBindingBySkuCode(String tenantId, String batchId, String skuCode) {
        return mapper.findMigrationBindingBySkuCode(tenantId, batchId, skuCode);
    }

    @Override
    public List<MigrationBindingView> listMigrationBindings(String tenantId, String batchId) {
        return mapper.listMigrationBindings(tenantId, batchId);
    }
}
