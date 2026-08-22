package com.jingshanghui.pos.catalog.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodePreview;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedBarcodeTemplateView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.WeightedSkuView;
import com.jingshanghui.pos.catalog.application.port.WeightedBarcodeSnapshotVerificationPort.FrozenMeasurement;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.domain.WeightedBarcodeRules;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeightedBarcodeServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-22T01:00:00Z");
    private static final String HASH = "a".repeat(64);

    private CatalogMapper mapper;
    private TrustedTenantContext context;
    private ScopeAuthorizationService authorization;
    private DomainAuditService audit;
    private PriceBookService prices;
    private CatalogOutboxService outbox;
    private WeightedBarcodeService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CatalogMapper.class);
        context = mock(TrustedTenantContext.class);
        authorization = mock(ScopeAuthorizationService.class);
        audit = mock(DomainAuditService.class);
        prices = mock(PriceBookService.class);
        outbox = mock(CatalogOutboxService.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        service = new WeightedBarcodeService(mapper, context, authorization, audit, prices, outbox,
            Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void publishesImmutableTemplateWithHistoryAuditAndOutbox() {
        WeightedBarcodeTemplateView draft = view("DRAFT", null, null, 0);
        WeightedBarcodeTemplateView published = view("PUBLISHED", HASH, NOW, 1);
        when(mapper.findWeightedBarcodeTemplate("TENANT_A", 501L)).thenReturn(draft, published);
        when(mapper.countWeightedBarcodeConflict(eq("TENANT_A"), eq(501L), eq("STORE"), eq(1101L),
            eq("22"), any(), any())).thenReturn(0);
        when(mapper.publishWeightedBarcodeTemplate(eq("TENANT_A"), eq(501L), eq(0), anyString(), any()))
            .thenReturn(1);

        WeightedBarcodeTemplateView result = service.publish(501L, 0);

        assertThat(result.state()).isEqualTo("PUBLISHED");
        verify(authorization).requireStoreAccess(1101L);
        verify(mapper).insertWeightedBarcodeHistory(eq("TENANT_A"), anyLong(), eq(501L), eq("PUBLISHED"),
            eq(1), anyString(), anyString(), any());
        verify(outbox).append(eq("TENANT_A"), eq("weighted-barcode-template.published.v1"),
            eq("WEIGHTED_BARCODE_TEMPLATE"), eq(501L), eq(1L), anyString());
    }

    @Test
    void conflictAndAmbiguousResolutionFailClosed() {
        WeightedBarcodeTemplateView draft = view("DRAFT", null, null, 0);
        when(mapper.findWeightedBarcodeTemplate("TENANT_A", 501L)).thenReturn(draft);
        when(mapper.countWeightedBarcodeConflict(anyString(), anyLong(), anyString(), anyLong(), anyString(),
            any(), any())).thenReturn(1);
        assertThatThrownBy(() -> service.publish(501L, 0)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CAT-WBC-022");

        WeightedBarcodeTemplateView published = view("PUBLISHED", HASH, NOW, 1);
        WeightedBarcodeTemplateView sameRank = new WeightedBarcodeTemplateView(502L, "STORE-WEIGHT-2", 1,
            "STORE", 1101L, "WEIGHT", "EAN13", "22", 13, 3, 5, 8, 5, 3, 10,
            "PUBLISHED", Instant.parse("2026-01-01T00:00:00Z"), null, HASH, NOW, 1);
        when(mapper.listWeightedBarcodeCandidates(eq("TENANT_A"), eq(1101L), anyString(), any()))
            .thenReturn(List.of(published, sameRank));
        assertThatThrownBy(() -> service.preview(1101L, ean13("220012300250"), NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CAT-WBC-027");
    }

    @Test
    void previewUsesTrustedStoreCatalogSkuAndPriceOwner() {
        WeightedBarcodeTemplateView published = view("PUBLISHED", HASH, NOW, 1);
        when(mapper.listWeightedBarcodeCandidates(eq("TENANT_A"), eq(1101L), anyString(), any()))
            .thenReturn(List.of(published));
        when(mapper.findWeightedSkuByCode("TENANT_A", "00123"))
            .thenReturn(new WeightedSkuView(101L, "00123", 301L, 3, "WEIGHT", "ACTIVE"));
        when(prices.resolve(101L, 301L, 1101L, NOW))
            .thenReturn(new ResolvedPrice(1990, "CNY", 201L, 301L, "STORE", NOW));

        WeightedBarcodePreview result = service.preview(1101L, ean13("220012300250"), NOW);

        assertThat(result.quantity()).isEqualByComparingTo("0.25");
        assertThat(result.amountMinor()).isEqualTo(498);
        assertThat(result.rawBarcode()).startsWith("22");
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void verifiesHistoricalFrozenMeasurementAndRejectsTamperedAmount() {
        WeightedBarcodeTemplateView retired = view("RETIRED", HASH, NOW, 2);
        when(mapper.findWeightedBarcodeTemplate("TENANT_A", 501L)).thenReturn(retired);
        when(mapper.findWeightedSkuIdentityByCode("TENANT_A", "00123"))
            .thenReturn(new WeightedSkuView(101L, "00123", 301L, 3, "WEIGHT", "INACTIVE"));
        var parsed = WeightedBarcodeRules.parse(new WeightedBarcodeRules.Template(501L, "STORE-WEIGHT", 1,
            "STORE", 1101L, "WEIGHT", "EAN13", "22", 13, 3, 5, 8, 5, 3, 10,
            Instant.parse("2026-01-01T00:00:00Z"), null, HASH), ean13("220012300250"), 1990, 3, NOW);
        FrozenMeasurement valid = new FrozenMeasurement(101L, "00123", 301L, parsed.rawBarcode(),
            parsed.encodedValue(), parsed.quantity(), parsed.amountMinor(), parsed.unitPriceMinor(),
            parsed.currency(), 501L, 1, HASH, parsed.parseSha256(), parsed.roundingApplied(), NOW);

        service.verify(1101L, valid);

        FrozenMeasurement tampered = new FrozenMeasurement(101L, "00123", 301L, parsed.rawBarcode(),
            parsed.encodedValue(), new BigDecimal("0.25"), 499, parsed.unitPriceMinor(), parsed.currency(),
            501L, 1, HASH, parsed.parseSha256(), parsed.roundingApplied(), NOW);
        assertThatThrownBy(() -> service.verify(1101L, tampered)).isInstanceOf(ServiceException.class)
            .hasMessageContaining("CAT-WBC-034");
    }

    private WeightedBarcodeTemplateView view(String state, String hash, Instant publishedAt, int version) {
        return new WeightedBarcodeTemplateView(501L, "STORE-WEIGHT", 1, "STORE", 1101L,
            "WEIGHT", "EAN13", "22", 13, 3, 5, 8, 5, 3, 10, state,
            Instant.parse("2026-01-01T00:00:00Z"), null, hash, publishedAt, version);
    }

    private String ean13(String firstTwelve) {
        return firstTwelve + WeightedBarcodeRules.checkDigit(firstTwelve);
    }
}
