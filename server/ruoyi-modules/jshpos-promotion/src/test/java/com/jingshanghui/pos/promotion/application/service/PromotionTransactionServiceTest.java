package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.*;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PRM-003 事务原子性、可信范围、原快照退款和幂等边界测试。 */
class PromotionTransactionServiceTest {
    private static final String TENANT = "TENANT_A";
    private static final String QUOTE = "01K5R000000000000000000001";
    private static final String LINE = "01K5R000000000000000000002";
    private static final String RULE = "01K5R000000000000000000003";
    private static final String SNAPSHOT = "01K5R000000000000000000004";
    private static final String ORDER = "01K5R000000000000000000005";
    private static final String COMMAND = "01K5R000000000000000000006";
    private static final String REFUND = "01K5R000000000000000000007";
    private static final String REFUND_COMMAND = "01K5R000000000000000000008";
    private static final String CORRELATION = "01K5R000000000000000000009";
    private static final Instant NOW = Instant.parse("2026-08-17T04:00:00Z");
    private final TrustedTenantContext tenants = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final StoreService stores = mock(StoreService.class);
    private final PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
    private final PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PromotionTransactionService service;
    private String fingerprint;

    @BeforeEach
    void setUp() {
        fingerprint = fingerprint();
        when(tenants.requirePrincipal()).thenReturn(new TrustedPrincipal(TENANT, 7L, 70L, "synthetic-user"));
        when(ids.next()).thenReturn("01K5R000000000000000000101", "01K5R000000000000000000102",
            "01K5R000000000000000000103", "01K5R000000000000000000104",
            "01K5R000000000000000000105", "01K5R000000000000000000106",
            "01K5R000000000000000000107", "01K5R000000000000000000108");
        when(persistence.lockQuote(TENANT, QUOTE)).thenReturn(new StoredQuote(QUOTE, 1101L, "TERM-01",
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "CNY", "c".repeat(64), fingerprint,
            PromotionEngine.ENGINE_VERSION, 31L, 1000, 100, 900));
        when(persistence.listQuoteLines(TENANT, QUOTE)).thenReturn(List.of(new StoredQuoteLine(LINE, 1,
            101L, new BigDecimal("3.000000"), 1000, 100, 900)));
        when(persistence.listQuoteAdjustments(TENANT, QUOTE)).thenReturn(List.of(new StoredAdjustment(LINE,
            "RULE", RULE, "RULE", 100, "APPLIED", true, 1)));
        when(persistence.listAppliedManualEvents(TENANT, QUOTE)).thenReturn(List.of());
        when(stores.businessDate(eq(1101L), any())).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        service = new PromotionTransactionService(tenants, authorization, stores, persistence,
            new TransactionAllocationEngine(), ids, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void freezesHeaderLinesCommandAuditAndOutboxInOneApplicationTransaction() {
        var result = service.freeze(freeze());

        assertThat(result.snapshotHash()).hasSize(64);
        assertThat(result.discountAmountMinor()).isEqualTo(100);
        assertThat(result.allocations()).hasSize(1);
        verify(authorization).requireStoreAccess(1101L);
        verify(persistence).insertSnapshot(argThat(value -> TENANT.equals(value.tenantId())
            && SNAPSHOT.equals(value.snapshotId()) && value.discountAmountMinor() == 100));
        verify(persistence).insertSnapshotLine(argThat(value -> value.sourceAllocationsJson().contains(RULE)));
        verify(persistence).insertCommand(argThat(value -> "PROMOTION_SNAPSHOT_FREEZE".equals(value.commandType())));
        verify(persistence).insertAudit(argThat(value -> "PROMOTION_SNAPSHOT_FROZEN".equals(value.actionCode())));
        verify(persistence).insertOutbox(argThat(value -> "promotion.snapshot.frozen.v1".equals(value.eventType())));
    }

    @Test
    void allocatesPartialRefundFromPersistedSnapshotAndAppendsLedger() {
        var frozen = service.freeze(freeze());
        ArgumentCaptor<SnapshotLineWrite> line = ArgumentCaptor.forClass(SnapshotLineWrite.class);
        verify(persistence).insertSnapshotLine(line.capture());
        SnapshotLineWrite saved = line.getValue();
        when(persistence.lockSnapshot(TENANT, SNAPSHOT)).thenReturn(new StoredSnapshot(SNAPSHOT, ORDER, QUOTE,
            1101L, "TERM-01", LocalDate.of(2026, 8, 17), "CNY", fingerprint, frozen.snapshotHash(),
            1000, 100, 900));
        when(persistence.listSnapshotLines(TENANT, SNAPSHOT)).thenReturn(List.of(new StoredSnapshotLine(saved.lineId(),
            saved.lineNo(), saved.skuId(), saved.quantity().setScale(6), saved.grossAmountMinor(), saved.discountAmountMinor(),
            saved.payableAmountMinor(), saved.sourceAllocationsJson(), saved.sourceAllocationsSha256())));
        when(persistence.listRefundHistory(TENANT, SNAPSHOT)).thenReturn(List.of());

        var result = service.allocateRefund(new AllocateRefund(REFUND_COMMAND, SNAPSHOT, REFUND,
            List.of(new RefundLine(LINE, BigDecimal.ONE)), CORRELATION));

        assertThat(result.grossAmountMinor()).isEqualTo(333);
        assertThat(result.recoveredDiscountMinor()).isEqualTo(33);
        assertThat(result.refundableAmountMinor()).isEqualTo(300);
        verify(persistence).insertRefundAllocation(argThat(value -> value.cumulativeQuantity().compareTo(BigDecimal.ONE) == 0));
        verify(persistence).insertOutbox(argThat(value -> "promotion.refund.allocated.v1".equals(value.eventType())));
    }

    @Test
    void previewsWithTheSameSnapshotAlgorithmWithoutAppendingFacts() {
        var frozen = service.freeze(freeze());
        ArgumentCaptor<SnapshotLineWrite> line = ArgumentCaptor.forClass(SnapshotLineWrite.class);
        verify(persistence).insertSnapshotLine(line.capture());
        SnapshotLineWrite saved = line.getValue();
        when(persistence.findSnapshot(TENANT, SNAPSHOT)).thenReturn(new StoredSnapshot(SNAPSHOT, ORDER, QUOTE,
            1101L, "TERM-01", LocalDate.of(2026, 8, 17), "CNY", fingerprint, frozen.snapshotHash(),
            1000, 100, 900));
        when(persistence.listSnapshotLines(TENANT, SNAPSHOT)).thenReturn(List.of(new StoredSnapshotLine(saved.lineId(),
            saved.lineNo(), saved.skuId(), saved.quantity().setScale(6), saved.grossAmountMinor(), saved.discountAmountMinor(),
            saved.payableAmountMinor(), saved.sourceAllocationsJson(), saved.sourceAllocationsSha256())));
        when(persistence.listRefundHistory(TENANT, SNAPSHOT)).thenReturn(List.of());
        clearInvocations(persistence);

        var result = service.previewRefund(SNAPSHOT, List.of(new RefundLine(LINE, BigDecimal.ONE)));

        assertThat(result.refundableAmountMinor()).isEqualTo(300);
        verify(persistence, never()).insertRefundAllocation(any());
        verify(persistence, never()).insertCommand(any());
        verify(persistence, never()).insertAudit(any());
        verify(persistence, never()).insertOutbox(any());
    }

    @Test
    void previewsPosSnapshotUsingImmutableQuoteFingerprintWhenHeaderCarriesSettlementFingerprint() {
        var frozen = service.freeze(freeze());
        ArgumentCaptor<SnapshotLineWrite> line = ArgumentCaptor.forClass(SnapshotLineWrite.class);
        verify(persistence).insertSnapshotLine(line.capture());
        SnapshotLineWrite saved = line.getValue();
        String settlementFingerprint = "9".repeat(64);
        when(persistence.findSnapshot(TENANT, SNAPSHOT)).thenReturn(new StoredSnapshot(SNAPSHOT, ORDER, QUOTE,
            1101L, "TERM-01", LocalDate.of(2026, 8, 17), "CNY", settlementFingerprint,
            frozen.snapshotHash(), 1000, 100, 900));
        when(persistence.findQuote(TENANT, QUOTE)).thenReturn(new StoredQuote(QUOTE, 1101L, "TERM-01",
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "CNY", "c".repeat(64), fingerprint,
            PromotionEngine.ENGINE_VERSION, 31L, 1000, 100, 900));
        when(persistence.listSnapshotLines(TENANT, SNAPSHOT)).thenReturn(List.of(new StoredSnapshotLine(saved.lineId(),
            saved.lineNo(), saved.skuId(), saved.quantity().setScale(6), saved.grossAmountMinor(), saved.discountAmountMinor(),
            saved.payableAmountMinor(), saved.sourceAllocationsJson(), saved.sourceAllocationsSha256())));
        when(persistence.listRefundHistory(TENANT, SNAPSHOT)).thenReturn(List.of());

        var result = service.previewRefund(SNAPSHOT, List.of(new RefundLine(LINE, BigDecimal.ONE)));

        assertThat(result.refundableAmountMinor()).isEqualTo(300);
        verify(persistence).findQuote(TENANT, QUOTE);
        verify(persistence, never()).insertRefundAllocation(any());
    }

    @Test
    void blocksPendingManualOrChangedFingerprintBeforeWritingSnapshot() {
        when(persistence.findPendingManualEvent(TENANT, QUOTE))
            .thenReturn(mock(ManualEvent.class))
            .thenReturn((ManualEvent) null);
        assertThatThrownBy(() -> service.freeze(freeze())).hasMessageContaining("待复核");
        verify(persistence, never()).insertSnapshot(any());

        assertThatThrownBy(() -> service.freeze(new FreezeSnapshot(COMMAND, SNAPSHOT, ORDER, QUOTE,
            "f".repeat(64), CORRELATION))).hasMessageContaining("指纹已变化");
    }

    @Test
    void blocksReusedBusinessRefundIdBeforeLedgerMutation() {
        when(persistence.lockSnapshot(TENANT, SNAPSHOT)).thenReturn(new StoredSnapshot(SNAPSHOT, ORDER, QUOTE,
            1101L, "TERM-01", LocalDate.of(2026, 8, 17), "CNY", fingerprint, "a".repeat(64), 1000, 100, 900));
        when(persistence.findRefund(TENANT, REFUND)).thenReturn(new ExistingRefund(SNAPSHOT, "b".repeat(64)));
        assertThatThrownBy(() -> service.allocateRefund(new AllocateRefund(REFUND_COMMAND, SNAPSHOT, REFUND,
            List.of(new RefundLine(LINE, BigDecimal.ONE)), CORRELATION))).hasMessageContaining("已经用于另一命令");
        verify(persistence, never()).insertRefundAllocation(any());
    }

    private FreezeSnapshot freeze() { return new FreezeSnapshot(COMMAND, SNAPSHOT, ORDER, QUOTE, fingerprint, CORRELATION); }

    private String fingerprint() {
        Map<String, Long> lineDiscounts = new LinkedHashMap<>(); lineDiscounts.put(LINE, 100L);
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", 1000L, "discountAmountMinor", 100L, "payableAmountMinor", 900L,
            "lineDiscounts", lineDiscounts, "appliedRuleIds", List.of(RULE))).sha256();
    }
}
