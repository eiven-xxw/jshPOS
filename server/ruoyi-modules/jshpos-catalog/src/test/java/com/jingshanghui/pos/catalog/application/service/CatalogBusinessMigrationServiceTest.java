package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.application.port.BusinessMigrationCatalogPort.ProductMigrationCommand;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort;
import com.jingshanghui.pos.catalog.application.port.CatalogMigrationPersistencePort.MigrationBindingView;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** 证明 Migration Owner 只能通过 Catalog 受控端口建立 DRAFT 与稳定绑定。 */
class CatalogBusinessMigrationServiceTest {
    private CatalogMigrationPersistencePort mapper;
    private CatalogApplicationService catalog;
    private CatalogBusinessMigrationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CatalogMigrationPersistencePort.class);
        catalog = mock(CatalogApplicationService.class);
        TrustedTenantContext tenant = mock(TrustedTenantContext.class);
        when(tenant.requireTenantId()).thenReturn("TENANT_A");
        service = new CatalogBusinessMigrationService(mapper, catalog, tenant);
    }

    @Test
    void createsDraftThroughCatalogOwnerAndFreezesBinding() {
        when(mapper.findCategoryByCode("TENANT_A", "FOOD"))
            .thenReturn(new DefinitionView(101L, "FOOD", "食品", "ACTIVE"));
        when(mapper.findUnitByCode("TENANT_A", "PCS"))
            .thenReturn(new DefinitionView(301L, "PCS", "件", "ACTIVE"));
        when(catalog.createProduct(any())).thenReturn(new ProductView(701L, 501L, "SPU-A", "SKU-A",
            "商品A", 101L, null, "STANDARD", "DRAFT", 0));

        var result = service.importDraftProduct(command("a".repeat(64)));

        assertThat(result.skuId()).isEqualTo(701L);
        assertThat(result.state()).isEqualTo("DRAFT");
        verify(mapper).insertMigrationBinding("TENANT_A", "01K2A000000000000000000001",
            "01K2A000000000000000000002", "a".repeat(64), 701L, 301L, "SKU-A");
        verify(catalog).createProduct(any());
    }

    @Test
    void replaysSameRowAndRejectsSameIdentityWithDifferentContent() {
        MigrationBindingView binding = new MigrationBindingView("01K2A000000000000000000001",
            "01K2A000000000000000000002", "a".repeat(64), 701L, 301L, "SKU-A");
        when(mapper.findMigrationBinding("TENANT_A", binding.batchId(), binding.rowId())).thenReturn(binding);
        when(mapper.findProduct("TENANT_A", 701L)).thenReturn(new ProductView(701L, 501L, "SPU-A", "SKU-A",
            "商品A", 101L, null, "STANDARD", "DRAFT", 0));

        assertThat(service.importDraftProduct(command("a".repeat(64))).replay()).isTrue();
        assertThatThrownBy(() -> service.importDraftProduct(command("b".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("相同迁移行对应不同内容");
        verify(catalog, never()).createProduct(any());
    }

    private ProductMigrationCommand command(String hash) {
        return new ProductMigrationCommand("01K2A000000000000000000001", "01K2A000000000000000000002",
            hash, "SPU-A", "SKU-A", "商品A", "FOOD", "食品", null, null,
            "STANDARD", "PCS", "件", 0, 1, 1, List.of("00001234"), "trace-dmt");
    }
}
