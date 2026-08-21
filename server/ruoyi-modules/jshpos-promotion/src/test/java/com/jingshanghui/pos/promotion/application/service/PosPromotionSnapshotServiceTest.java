package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort.SnapshotCommand;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort.SnapshotLine;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PosPromotionSnapshotServiceTest {
    private static final String EVENT = "01K2A000000000000000000201";
    private static final String CORRELATION = "01K2A000000000000000000211";
    private static final String QUOTE = "01K2A000000000000000000052";
    private static final String SNAPSHOT = "01K2A000000000000000000051";
    private static final String ORDER = "01K2A000000000000000000031";
    private static final String TERMINAL = "01K2A000000000000000000011";
    private static final String LINE = "01K2A000000000000000000041";
    private static final String FINGERPRINT = "2".repeat(64);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
    private final PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
    private final PosPromotionSnapshotService service =
        new PosPromotionSnapshotService(context, authorization, persistence, ids);

    @Test
    void ingestsOfflineQuoteAndExactSnapshotThroughPromotionOwner() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic"));
        when(persistence.findPackage("TENANT_A", 1101L, 1L)).thenReturn(mock(PackageView.class));
        when(ids.next()).thenReturn("01K2A000000000000000000301", "01K2A000000000000000000302",
            "01K2A000000000000000000303", "01K2A000000000000000000304");
        assertThat(snapshotHash()).isEqualTo("b747993a40606c06fcd0799286ffae1f50249fc7ccdd0c07c46a9d3e2f04a1d8");
        SnapshotCommand command = command(snapshotHash());

        service.ingest(command);

        verify(authorization).requireStoreAccess(1101L);
        verify(persistence).insertQuote(any());
        verify(persistence).insertQuoteLine(any());
        verify(persistence).insertSnapshot(any());
        verify(persistence).insertSnapshotLine(any());
        verify(persistence).insertAudit(any());
        verify(persistence).insertOutbox(any());
    }

    @Test
    void rejectsTamperedSnapshotBeforeAnyOwnerWrite() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic"));
        when(persistence.findPackage("TENANT_A", 1101L, 1L)).thenReturn(mock(PackageView.class));

        assertThatThrownBy(() -> service.ingest(command("f".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PRM-POS-006");
        verify(persistence, never()).insertSnapshot(any());
    }

    private SnapshotCommand command(String hash) {
        return new SnapshotCommand(EVENT, CORRELATION, QUOTE, SNAPSHOT, ORDER, 1101L, TERMINAL,
            LocalDate.parse("2026-08-16"), 1, "promotion-engine-1.0.0", FINGERPRINT, hash,
            1299, 200, 1099, Instant.parse("2026-08-16T02:00:00Z"), List.of(line()));
    }

    private SnapshotLine line() {
        return new SnapshotLine(LINE, 1, 701L, new BigDecimal("1.000000"), 1299,
            1299, 200, 1099, Map.of("RULE:01K2A000000000000000000061", 200L));
    }

    private String snapshotHash() {
        SnapshotLine line = line();
        String sources = CanonicalJson.from(new LinkedHashMap<>(line.sourceAllocations())).sha256();
        Map<String, Object> canonicalLine = Map.of("lineId", LINE, "lineNo", 1, "skuId", 701L,
            "quantity", "1.000000", "grossAmountMinor", 1299L, "discountAmountMinor", 200L,
            "payableAmountMinor", 1099L, "sourceAllocationsSha256", sources);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("snapshotId", SNAPSHOT); content.put("orderId", ORDER); content.put("quoteId", QUOTE);
        content.put("storeId", 1101L); content.put("terminalId", TERMINAL); content.put("currency", "CNY");
        content.put("quoteFingerprint", FINGERPRINT); content.put("grossAmountMinor", 1299L);
        content.put("discountAmountMinor", 200L); content.put("payableAmountMinor", 1099L);
        content.put("lines", List.of(canonicalLine));
        return CanonicalJson.from(content).sha256();
    }
}
