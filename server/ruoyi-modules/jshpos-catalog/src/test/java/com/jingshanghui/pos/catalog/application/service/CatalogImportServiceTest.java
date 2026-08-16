package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.importing.CatalogImportPreflight;
import com.jingshanghui.pos.catalog.application.importing.CatalogImportRow;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportBatchView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.ImportPreflightView;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogImportServiceTest {

    private CatalogMapper mapper;
    private CatalogImportService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CatalogMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        service = new CatalogImportService(mapper, new CatalogImportPreflight(), context,
            mock(DomainAuditService.class), Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC),
            new ObjectMapper(), mock(CatalogOutboxService.class));
    }

    @Test
    void preflightsPersistsAndPublishesByAtomicTenantBinding() {
        CatalogImportRow row = new CatalogImportRow(1, "SPU-A", "SKU-A", "合成商品", "CAT", "BRAND",
            "STANDARD", "PCS", "1", 1L, 1L, List.of("001234"));
        when(mapper.findImportByKey("TENANT_A", "import-key-0001")).thenReturn(null);
        when(mapper.findCurrentImportBatch("TENANT_A")).thenReturn((Long) null);
        when(mapper.findImport(eq("TENANT_A"), anyLong())).thenAnswer(invocation ->
            new ImportBatchView(invocation.getArgument(1), "import-key-0001", "a".repeat(64), 1, 0, "PRECHECKED", null));
        ImportPreflightView result = service.preflight("import-key-0001", List.of(row));
        assertThat(result.batch().state()).isEqualTo("PRECHECKED");
        verify(mapper).insertImportBatch(eq("TENANT_A"), anyLong(), eq("import-key-0001"), anyString(), eq(1), eq(0), eq("PRECHECKED"), eq(null));
        verify(mapper).insertImportRecord(eq("TENANT_A"), anyLong(), anyLong(), eq(1), eq("SKU-A"), anyString(), anyString());

        ImportBatchView prechecked = new ImportBatchView(11L, "import-key-0001", "a".repeat(64), 1, 0, "PRECHECKED", null);
        ImportBatchView published = new ImportBatchView(11L, "import-key-0001", "a".repeat(64), 1, 0, "PUBLISHED", null);
        when(mapper.findImport("TENANT_A", 11L)).thenReturn(prechecked, published);
        when(mapper.findCurrentImportBatch("TENANT_A")).thenReturn((Long) null);
        when(mapper.publishImportBatch(eq("TENANT_A"), eq(11L), any())).thenReturn(1);
        assertThat(service.publish(11L).state()).isEqualTo("PUBLISHED");
        verify(mapper).activateImportBatch(eq("TENANT_A"), anyLong(), eq(11L), eq(null), any());
    }

    @Test
    void rejectsIdempotencyCollisionAndInvalidPublish() {
        CatalogImportRow row = new CatalogImportRow(1, "SPU-A", "SKU-A", "A", "CAT", "BRAND",
            "STANDARD", "PCS", "1", 1L, 1L, List.of());
        when(mapper.findImportByKey("TENANT_A", "import-key-0001"))
            .thenReturn(new ImportBatchView(1L, "import-key-0001", "0".repeat(64), 1, 0, "PRECHECKED", null));
        assertThatThrownBy(() -> service.preflight("import-key-0001", List.of(row)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("不同摘要");
        verify(mapper, never()).insertImportBatch(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());

        when(mapper.findImport("TENANT_A", 2L))
            .thenReturn(new ImportBatchView(2L, "import-key-0002", "a".repeat(64), 1, 1, "REJECTED", null));
        assertThatThrownBy(() -> service.publish(2L)).isInstanceOf(ServiceException.class).hasMessageContaining("零错误");
    }
}
