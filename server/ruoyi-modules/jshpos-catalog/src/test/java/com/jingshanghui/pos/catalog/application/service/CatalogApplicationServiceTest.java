package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.DefinitionView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ProductView;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogApplicationServiceTest {

    private CatalogMapper mapper;
    private TrustedTenantContext context;
    private DomainAuditService audit;
    private CatalogApplicationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CatalogMapper.class);
        context = mock(TrustedTenantContext.class);
        audit = mock(DomainAuditService.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        service = new CatalogApplicationService(mapper, context, audit, new ObjectMapper(), mock(CatalogOutboxService.class));
    }

    @Test
    void injectsTrustedTenantAcrossDefinitionsAndProductAggregate() {
        DefinitionView activeCategory = new DefinitionView(101L, "FOOD", "食品", "ACTIVE");
        DefinitionView activeBrand = new DefinitionView(102L, "BRAND", "品牌", "ACTIVE");
        DefinitionView activeUnit = new DefinitionView(103L, "PCS", "件", "ACTIVE");
        when(mapper.findCategory("TENANT_A", 101L)).thenReturn(activeCategory);
        when(mapper.findBrand("TENANT_A", 102L)).thenReturn(activeBrand);
        when(mapper.findUnit("TENANT_A", 103L)).thenReturn(activeUnit);
        when(mapper.findProduct(eq("TENANT_A"), anyLong())).thenAnswer(invocation ->
            new ProductView(invocation.getArgument(1), 501L, "SPU-A", "SKU-A", "合成商品",
                101L, 102L, "STANDARD", "DRAFT", 0));

        ProductView result = service.createProduct(new CatalogApplicationService.CreateProduct(
            "spu-a", "sku-a", "合成商品", 101L, 102L, "standard", Map.of("color", "red"),
            List.of(new CatalogApplicationService.UnitInput(103L, 1L, 1L, true, List.of("001234")))
        ));

        assertThat(result.skuCode()).isEqualTo("SKU-A");
        verify(mapper).insertSpu(eq("TENANT_A"), anyLong(), eq("SPU-A"), eq("合成商品"), eq(101L), eq(102L), any());
        verify(mapper).insertSku(eq("TENANT_A"), anyLong(), anyLong(), eq("SKU-A"), eq("合成商品"), eq("STANDARD"), any());
        verify(mapper).insertSkuUnit(eq("TENANT_A"), anyLong(), anyLong(), eq(103L), eq(1L), eq(1L), eq(true));
        verify(mapper).insertBarcode(eq("TENANT_A"), anyLong(), anyLong(), anyLong(), eq("001234"), eq("STANDARD"));
        verify(audit).append(eq("PRODUCT_CREATED"), eq("SKU"), any(), eq(null), any(), any());
    }

    @Test
    void failsBeforePersistenceForInvalidUnitShapeAndStatusFilter() {
        when(mapper.findCategory("TENANT_A", 101L)).thenReturn(new DefinitionView(101L, "FOOD", "食品", "ACTIVE"));
        assertThatThrownBy(() -> service.createProduct(new CatalogApplicationService.CreateProduct(
            "SPU-A", "SKU-A", "A", 101L, null, "STANDARD", Map.of(),
            List.of(new CatalogApplicationService.UnitInput(103L, 1L, 1L, false, List.of())))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("主单位");
        verify(mapper, never()).insertSpu(any(), any(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.listProducts("DELETED", 100)).isInstanceOf(ServiceException.class);
    }
}
