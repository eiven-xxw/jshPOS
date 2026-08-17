package com.jingshanghui.pos.reporting.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.port.ReportingPersistencePort;
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
        when(context.requireTenantId()).thenReturn("tenant_alpha");
        when(persistence.activeProjectionVersion("tenant_alpha","SALES")).thenReturn("g5d-v1");
        when(persistence.activeProjectionVersion("tenant_alpha","INVENTORY_COST")).thenReturn(null);
        LocalDate day=LocalDate.of(2026,8,17);
        SalesDailyView row=new SalesDailyView(day,1L,11L,"T1",7L,"CNY",1,0,0,100,10,0,90,0,90,0,0,1,"CURRENT");
        when(persistence.querySales("tenant_alpha","g5d-v1",day,day,11L,"T1",7L)).thenReturn(List.of(row));
        ReportQueryService service=new ReportQueryService(persistence,context,auth);
        assertThat(service.sales(new SalesQuery(day,day,11L,"T1",7L))).containsExactly(row);
        assertThat(service.inventoryCost(new InventoryCostQuery(day,day,11L,null,null))).isEmpty();
        verify(auth,times(2)).requireStoreAccess(11L);
        verify(persistence,never()).queryInventoryCost(anyString(),anyString(),any(),any(),anyLong(),any(),any());
    }
}
