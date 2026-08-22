package com.jingshanghui.pos.catalog.application.service;

import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceBookView;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.catalog.application.port.ShelfLabelPriceEventPort;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceBookServiceTest {

    @Test
    void publishesCanonicalStoreVersionOnlyAfterDataScopeAuthorization() {
        CatalogMapper mapper = mock(CatalogMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
        DomainAuditService audit = mock(DomainAuditService.class);
        CatalogOutboxService outbox = mock(CatalogOutboxService.class);
        ShelfLabelPriceEventPort shelfLabels = mock(ShelfLabelPriceEventPort.class);
        PriceBookView draft = new PriceBookView(99L, "STORE-PRICE", "门店价", 2,
            "STORE", 11L, "DRAFT", null);
        PriceBookView published = new PriceBookView(99L, "STORE-PRICE", "门店价", 2,
            "STORE", 11L, "PUBLISHED", "a".repeat(64));
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(mapper.findPriceBook("TENANT_A", 99L)).thenReturn(draft, published);
        when(mapper.listPriceCanonicalRows("TENANT_A", 99L)).thenReturn(List.of("row-a", "row-b"));
        when(mapper.publishPriceBook(eq("TENANT_A"), eq(99L), any(), any())).thenReturn(1);
        PriceBookService service = new PriceBookService(mapper, context, authorization, audit,
            Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC), outbox, shelfLabels);

        PriceBookView result = service.publish(99L);

        assertThat(result.state()).isEqualTo("PUBLISHED");
        verify(authorization).requireStoreAccess(11L);
        verify(mapper).publishPriceBook(eq("TENANT_A"), eq(99L), any(), any());
        verify(outbox).append(eq("TENANT_A"), eq("price-book.published.v1"), eq("PRICE_BOOK"),
            eq(99L), eq(2L), any());
        verify(shelfLabels).handle(any());
    }
}
