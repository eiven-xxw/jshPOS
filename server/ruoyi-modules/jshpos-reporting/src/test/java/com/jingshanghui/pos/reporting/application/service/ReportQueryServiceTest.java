package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
import com.jingshanghui.pos.reporting.application.port.ReportingBatchReadPort;
import com.jingshanghui.pos.reporting.application.port.SalesPageCursorCodec;
import com.jingshanghui.pos.reporting.infrastructure.security.HmacSalesPageCursorCodec;
import com.jingshanghui.pos.reporting.infrastructure.security.HmacInventoryCostPageCursorCodec;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ReportQueryServiceTest {
    @Test void queriesOnlyActiveVersionInsideTrustedStoreScope() {
        ReportingPersistencePort persistence=mock(ReportingPersistencePort.class);
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        ScopeAuthorizationService auth=mock(ScopeAuthorizationService.class);
        ReportingBatchReadPort batch=mock(ReportingBatchReadPort.class);
        SalesPageCursorCodec cursors=new HmacSalesPageCursorCodec(new byte[32]);
        when(context.requireTenantId()).thenReturn("tenant_alpha");
        when(persistence.activeProjectionVersion("tenant_alpha","SALES")).thenReturn("g5d-v1");
        when(persistence.activeProjectionVersion("tenant_alpha","INVENTORY_COST")).thenReturn(null);
        LocalDate day=LocalDate.of(2026,8,17);
        SalesDailyView row=new SalesDailyView(day,1L,11L,"T1",7L,"CNY",1,0,0,100,10,0,90,0,90,0,0,1,"CURRENT");
        when(persistence.querySales("tenant_alpha","g5d-v1",day,day,11L,"T1",7L)).thenReturn(List.of(row));
        ReportQueryService service=new ReportQueryService(persistence,batch,cursors,
            new HmacInventoryCostPageCursorCodec(new byte[32]),context,auth);
        assertThat(service.sales(new SalesQuery(day,day,11L,"T1",7L))).containsExactly(row);
        assertThat(service.inventoryCost(new InventoryCostQuery(day,day,11L,null,null))).isEmpty();
        verify(auth,times(2)).requireStoreAccess(11L);
        verify(persistence,never()).queryInventoryCost(anyString(),anyString(),any(),any(),anyLong(),any(),any());
    }

    @Test void returnsBoundedSalesPageAndSignedContinuationWithoutTrustingClientTenant() {
        ReportingPersistencePort persistence=mock(ReportingPersistencePort.class);
        ReportingBatchReadPort batch=mock(ReportingBatchReadPort.class);
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        ScopeAuthorizationService auth=mock(ScopeAuthorizationService.class);
        SalesPageCursorCodec cursors=new HmacSalesPageCursorCodec("sales-page-cursor-key-32-bytes!!".getBytes());
        LocalDate day=LocalDate.of(2026,8,17);
        when(context.requireTenantId()).thenReturn("tenant_alpha");
        when(persistence.activeProjectionVersion("tenant_alpha","SALES")).thenReturn("g5d-v1");
        SalesDailyView first=new SalesDailyView(day,1L,11L,"T1",7L,"CNY",1,0,0,100,0,0,100,0,100,0,0,0,"CURRENT");
        SalesDailyView second=new SalesDailyView(day,1L,11L,"T2",8L,"CNY",1,0,0,200,0,0,200,0,200,0,0,0,"CURRENT");
        when(batch.readSales(any())).thenReturn(List.of(first,second));
        ReportQueryService service=new ReportQueryService(persistence,batch,cursors,
            new HmacInventoryCostPageCursorCodec(new byte[32]),context,auth);
        SalesPageView page=service.salesPage(new SalesPageQuery(day,day,11L,null,null,null,1));
        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        verify(auth).requireStoreAccess(11L);
        verify(batch).readSales(argThat(request -> request.tenantId().equals("tenant_alpha")
            && request.storeIds().equals(List.of(11L)) && request.limit()==2));
    }

    @Test void returnsBoundedInventoryPageAndSignedContinuationFromTrustedScope() {
        ReportingPersistencePort persistence=mock(ReportingPersistencePort.class);
        ReportingBatchReadPort batch=mock(ReportingBatchReadPort.class);
        TrustedTenantContext context=mock(TrustedTenantContext.class);
        ScopeAuthorizationService auth=mock(ScopeAuthorizationService.class);
        var inventoryCursors=new HmacInventoryCostPageCursorCodec(
            "inventory-page-cursor-key-32-bytes".getBytes());
        LocalDate day=LocalDate.of(2026,8,17);
        when(context.requireTenantId()).thenReturn("tenant_alpha");
        when(persistence.activeProjectionVersion("tenant_alpha","INVENTORY_COST")).thenReturn("g5d-v1");
        InventoryCostDailyView first=inventory(day,"W1",101L,"1.000000");
        InventoryCostDailyView second=inventory(day,"W1",102L,"2.000000");
        when(batch.readInventoryCost(any())).thenReturn(List.of(first,second));
        ReportQueryService service=new ReportQueryService(persistence,batch,
            new HmacSalesPageCursorCodec(new byte[32]),inventoryCursors,context,auth);

        InventoryCostPageView page=service.inventoryCostPage(
            new InventoryCostPageQuery(day,day,11L,null,null,null,1));

        assertThat(page.items()).containsExactly(first);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank();
        verify(auth).requireStoreAccess(11L);
        verify(batch).readInventoryCost(argThat(request -> request.tenantId().equals("tenant_alpha")
            && request.storeIds().equals(List.of(11L)) && request.limit()==2));
    }

    private InventoryCostDailyView inventory(LocalDate day,String warehouseId,Long skuId,String value) {
        java.math.BigDecimal decimal=new java.math.BigDecimal(value);
        return new InventoryCostDailyView(day,1L,11L,warehouseId,skuId,"CNY",decimal,decimal,decimal,
            decimal,decimal,decimal,decimal,decimal,decimal,decimal,decimal,decimal,"CURRENT");
    }
}
