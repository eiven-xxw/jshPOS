package com.jingshanghui.pos.catalog.application.port;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;

import java.util.List;

/**
 * Catalog Owner 内部的开业商品迁移持久化端口。
 *
 * <p>只暴露定义读取、商品读取和不可变迁移绑定，不向应用层暴露通用 Mapper CRUD。</p>
 */
public interface CatalogMigrationPersistencePort {
    DefinitionView findCategoryByCode(String tenantId, String code);
    DefinitionView findBrandByCode(String tenantId, String code);
    DefinitionView findUnitByCode(String tenantId, String code);
    ProductView findProductByCode(String tenantId, String skuCode);
    ProductView findProduct(String tenantId, Long skuId);
    int insertMigrationBinding(String tenantId, String batchId, String rowId, String rowSha256,
                               Long skuId, Long baseUnitId, String skuCode);
    MigrationBindingView findMigrationBinding(String tenantId, String batchId, String rowId);
    MigrationBindingView findMigrationBindingBySkuCode(String tenantId, String batchId, String skuCode);
    List<MigrationBindingView> listMigrationBindings(String tenantId, String batchId);

    /**
     * Catalog Owner 保存的迁移行到正式商品身份不可变绑定。
     * @param batchId 迁移批次 ULID
     * @param rowId 冻结迁移行 ULID
     * @param rowSha256 规范行内容 SHA-256
     * @param skuId 正式 SKU 主键
     * @param baseUnitId 正式基础单位主键
     * @param skuCode 租户内 SKU 编码
     */
    record MigrationBindingView(String batchId, String rowId, String rowSha256,
                                Long skuId, Long baseUnitId, String skuCode) {
    }
}
