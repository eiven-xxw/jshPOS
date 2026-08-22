package com.jingshanghui.pos.procurement.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.ReplenishmentInventorySnapshotPort;
import com.jingshanghui.pos.inventory.application.port.ReplenishmentInventorySnapshotPort.InventorySnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.model.ReplenishmentModels.*;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentProcurementSnapshotPort;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentProcurementSnapshotPort.SupplierSnapshot;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentPurchaseDraftPort;
import com.jingshanghui.pos.procurement.domain.ReplenishmentHash;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ReplenishmentPersistenceParams.SuggestionWrite;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ReplenishmentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证补货规则冻结、确定性生成和检查点过期的跨 Owner 边界。 */
class ReplenishmentServiceTest {

    private static final String POLICY = "01K2A000000000000000000201";
    private static final String ITEM = "01K2A000000000000000000202";
    private static final String RUN = "01K2A000000000000000000203";
    private static final String SUGGESTION = "01K2A000000000000000000204";
    private static final String WAREHOUSE = "01K2A000000000000000000010";
    private static final String SUPPLIER = "01K2A000000000000000000120";
    private static final String ORDER = "01K2A000000000000000000205";
    private static final Instant NOW = Instant.parse("2026-08-22T01:00:00Z");

    private final ReplenishmentMapper mapper = mock(ReplenishmentMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final InventoryCatalogSnapshotPort catalog = mock(InventoryCatalogSnapshotPort.class);
    private final ReplenishmentInventorySnapshotPort inventory = mock(ReplenishmentInventorySnapshotPort.class);
    private final ReplenishmentProcurementSnapshotPort procurement = mock(ReplenishmentProcurementSnapshotPort.class);
    private final ReplenishmentPurchaseDraftPort drafts = mock(ReplenishmentPurchaseDraftPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ReplenishmentService service;

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 202L, 1L, "operator"));
        service = new ReplenishmentService(mapper, context, authorization, catalog, inventory, procurement,
            drafts, new UlidGenerator(clock), new ObjectMapper(), clock);
    }

    @Test
    void createPolicyFreezesCatalogAndSupplierSnapshotsWithoutInventoryWrites() {
        PolicyView draft = policy("DRAFT", null, 0);
        when(mapper.findPolicy("TENANT_A", POLICY)).thenReturn(null, draft);
        when(mapper.findPolicyItems("TENANT_A", POLICY)).thenReturn(List.of(item()));
        when(catalog.requireUnit(701L, 302L)).thenReturn(unit());
        when(procurement.requireActiveSupplier(SUPPLIER))
            .thenReturn(new SupplierSnapshot(SUPPLIER, "SYN", "虚构供应商", "ACTIVE"));

        PolicyDetail result = service.createPolicy(new CreatePolicy(POLICY, 1101L, WAREHOUSE, 1, NOW,
            List.of(new PolicyItemInput(ITEM, 701L, 302L, SUPPLIER, new BigDecimal("5"),
                new BigDecimal("20"), BigDecimal.ONE, new BigDecimal("2"), true, 100, 0)),
            "idem-policy", "trace-policy"));

        assertThat(result.policy().state()).isEqualTo("DRAFT");
        verify(mapper).insertPolicy(any());
        verify(mapper).insertPolicyItem(any());
        verifyNoInteractions(inventory, drafts);
    }

    @Test
    void generationUsesFrozenRuleInventoryCheckpointAndConfirmedTransit() {
        PolicyView published = policy("PUBLISHED", "a".repeat(64), 1);
        when(mapper.lockPolicy("TENANT_A", POLICY)).thenReturn(published);
        when(mapper.findPolicy("TENANT_A", POLICY)).thenReturn(published);
        when(mapper.findPolicyItems("TENANT_A", POLICY)).thenReturn(List.of(item()));
        when(mapper.findRunByIdempotencyKey("TENANT_A", "idem-run")).thenReturn(null);
        when(inventory.requireReplenishmentSnapshot(WAREHOUSE, 701L)).thenReturn(snapshot(10));
        when(procurement.confirmedInTransitBase(WAREHOUSE, 701L, SUPPLIER)).thenReturn(new BigDecimal("2.000000"));
        when(mapper.listOpenSuggestionsForUpdate("TENANT_A", WAREHOUSE, 701L)).thenReturn(List.of());
        when(mapper.completeRun(any())).thenReturn(1);
        when(mapper.findRun("TENANT_A", RUN)).thenReturn(null, new GenerationRunView(RUN, POLICY, 1101L,
            WAREHOUSE, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "b".repeat(64), "COMPLETED", 1, 1));
        when(mapper.listSuggestionsByRun("TENANT_A", RUN)).thenReturn(List.of());

        service.generate(new GenerateSuggestions(RUN, POLICY, NOW, "idem-run", "trace-run"));

        ArgumentCaptor<SuggestionWrite> captured = ArgumentCaptor.forClass(SuggestionWrite.class);
        verify(mapper).insertSuggestion(captured.capture());
        assertThat(captured.getValue().effectiveQuantity()).isEqualByComparingTo("3.000000");
        assertThat(captured.getValue().requiredBaseQuantity()).isEqualByComparingTo("17.000000");
        assertThat(captured.getValue().suggestedPurchaseQuantity()).isEqualByComparingTo("18.000000");
        assertThat(captured.getValue().inputLedgerSequence()).isEqualTo(10);
        verify(drafts, never()).createReplenishmentDraft(any());
    }

    @Test
    void draftFailsClosedAsStaleWhenInventoryCheckpointChanged() {
        SuggestionView approved = suggestion("APPROVED", 2, 10);
        when(mapper.findIdempotency("TENANT_A", "idem-draft")).thenReturn(null);
        when(mapper.lockSuggestion("TENANT_A", SUGGESTION)).thenReturn(approved);
        when(inventory.requireReplenishmentSnapshot(WAREHOUSE, 701L)).thenReturn(snapshot(11));
        when(catalog.requireUnit(701L, 302L)).thenReturn(unit());
        when(mapper.updateSuggestionState(any())).thenReturn(1);
        when(mapper.findSuggestion("TENANT_A", SUGGESTION)).thenReturn(suggestion("STALE", 3, 10));

        SuggestionView result = service.createPurchaseDraft(new CreatePurchaseDraft(SUGGESTION, 2, ORDER,
            java.time.LocalDate.of(2026, 8, 25), "idem-draft", "trace-draft"));

        assertThat(result.state()).isEqualTo("STALE");
        verify(drafts, never()).createReplenishmentDraft(any());
        verify(mapper).insertEvent(any());
        verify(mapper).insertAudit(any());
        verify(mapper).insertOutbox(any());
    }

    @Test
    void retryAfterLaterStateReturnsCurrentFactButChangedContentFailsClosed() {
        SuggestionView approved = suggestion("APPROVED", 2, 10);
        String originalHash = ReplenishmentHash.sha256(ReplenishmentHash.canonical(List.of(
            "REVIEWED", SUGGESTION, 0L, "人工复核", "idem-review")));
        when(mapper.lockSuggestion("TENANT_A", SUGGESTION)).thenReturn(approved);
        when(mapper.findSuggestion("TENANT_A", SUGGESTION)).thenReturn(approved);
        when(mapper.findIdempotency("TENANT_A", "idem-review"))
            .thenReturn(new IdempotencyView("idem-review", originalHash, SUGGESTION, "REVIEWED", null));

        SuggestionView retry = service.review(new SuggestionCommand(SUGGESTION, 0,
            "idem-review", "人工复核", "trace-review"));

        assertThat(retry.state()).isEqualTo("APPROVED");
        assertThatThrownBy(() -> service.review(new SuggestionCommand(SUGGESTION, 0,
            "idem-review", "篡改原因", "trace-review")))
            .isInstanceOf(org.dromara.common.core.exception.ServiceException.class)
            .hasMessageContaining("RPL-IDEM-004");
        verify(mapper, never()).updateSuggestionState(any());
    }

    private PolicyView policy(String state, String hash, long version) {
        return new PolicyView(POLICY, 1101L, WAREHOUSE, 1, state,
            LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC), hash, version);
    }

    private PolicyItemView item() {
        return new PolicyItemView(ITEM, POLICY, 701L, "SYN-SKU", 301L, 302L, 1, 1,
            SUPPLIER, new BigDecimal("5.000000"), new BigDecimal("20.000000"), BigDecimal.ONE.setScale(6),
            new BigDecimal("2.000000"), true, 100, 0, "c".repeat(64));
    }

    private SkuUnitSnapshot unit() {
        return new SkuUnitSnapshot(701L, "SYN-SKU", 302L, 301L, 1, 1, false);
    }

    private InventorySnapshot snapshot(long sequence) {
        return new InventorySnapshot(1101L, WAREHOUSE, 701L, new BigDecimal("1.000000"),
            BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6),
            new BigDecimal("1.000000"), sequence, sequence);
    }

    private SuggestionView suggestion(String state, long version, long sequence) {
        return new SuggestionView(SUGGESTION, RUN, POLICY, ITEM, 1101L, WAREHOUSE, 701L, "SYN-SKU",
            301L, 302L, SUPPLIER, new BigDecimal("1.000000"), BigDecimal.ZERO.setScale(6),
            BigDecimal.ZERO.setScale(6), BigDecimal.ZERO.setScale(6), new BigDecimal("1.000000"),
            new BigDecimal("2.000000"), new BigDecimal("3.000000"), new BigDecimal("5.000000"),
            new BigDecimal("20.000000"), new BigDecimal("17.000000"), new BigDecimal("18.000000"),
            BigDecimal.ONE.setScale(6), new BigDecimal("2.000000"), 1, 1, sequence, sequence,
            "BELOW_MINIMUM_REPLENISH_TO_MAXIMUM", state, "d".repeat(64), null, null,
            201L, 202L, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), version);
    }
}
