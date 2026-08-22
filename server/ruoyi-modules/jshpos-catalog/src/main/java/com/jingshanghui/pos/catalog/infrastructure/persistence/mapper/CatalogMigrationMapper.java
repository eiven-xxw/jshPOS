package com.jingshanghui.pos.catalog.infrastructure.persistence.mapper;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort.MigrationBindingView;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** cat_migration_product 只追加绑定及迁移所需 Catalog 只读查询；业务 SQL 位于 XML。 */
public interface CatalogMigrationMapper {
    DefinitionView findCategoryByCode(@Param("tenantId") String tenantId, @Param("code") String code);
    DefinitionView findBrandByCode(@Param("tenantId") String tenantId, @Param("code") String code);
    DefinitionView findUnitByCode(@Param("tenantId") String tenantId, @Param("code") String code);
    ProductView findProductByCode(@Param("tenantId") String tenantId, @Param("skuCode") String skuCode);
    ProductView findProduct(@Param("tenantId") String tenantId, @Param("skuId") Long skuId);
    int insertMigrationBinding(@Param("tenantId") String tenantId, @Param("batchId") String batchId,
                               @Param("rowId") String rowId, @Param("rowSha256") String rowSha256,
                               @Param("skuId") Long skuId, @Param("baseUnitId") Long baseUnitId,
                               @Param("skuCode") String skuCode);
    MigrationBindingView findMigrationBinding(@Param("tenantId") String tenantId,
                                               @Param("batchId") String batchId,
                                               @Param("rowId") String rowId);
    MigrationBindingView findMigrationBindingBySkuCode(@Param("tenantId") String tenantId,
                                                        @Param("batchId") String batchId,
                                                        @Param("skuCode") String skuCode);
    List<MigrationBindingView> listMigrationBindings(@Param("tenantId") String tenantId,
                                                      @Param("batchId") String batchId);
}
