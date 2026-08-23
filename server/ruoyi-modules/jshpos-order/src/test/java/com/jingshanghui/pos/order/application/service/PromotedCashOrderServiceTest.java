package com.jingshanghui.pos.order.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.WeightedBarcodeSnapshotVerificationPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.PromotedLine;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.MeasuredBarcodeSnapshot;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort.Line;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort.MemberBenefit;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort.Snapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.domain.PromotedOrderSnapshotCodec;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromotedCashOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-17T06:00:00Z");
    private static final String ORDER = "01K5N000000000000000000001";
    private static final String LINE = "01K5R000000000000000000001";
    private static final String SHIFT = "01K5H000000000000000000001";
    private static final String SNAPSHOT = "01K5S000000000000000000001";
    private static final String QUOTE = "01K5Q000000000000000000001";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_C = "c".repeat(64);

    private final PromotedOrderRepository repository = mock(PromotedOrderRepository.class);
    private final OrderMapper mapper = mock(OrderMapper.class);
    private final PromotionSnapshotQueryPort promotions = mock(PromotionSnapshotQueryPort.class);
    private final WeightedBarcodeSnapshotVerificationPort weightedBarcodes =
        mock(WeightedBarcodeSnapshotVerificationPort.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UlidGenerator ulids = new UlidGenerator(clock);
    private final IdempotencyService idempotency = new IdempotencyService(mapper, ulids, new ObjectMapper());
    private final OrderJournalService journal = new OrderJournalService(mapper, ulids);
    private final OrderFinalityGuardService finalityGuard = new OrderFinalityGuardService(mapper);
    private final PromotedCashOrderService service = new PromotedCashOrderService(repository, mapper, promotions,
        weightedBarcodes, context, authorization, idempotency, journal, finalityGuard, ulids);

    @BeforeEach
    void configureTrustedSyntheticContext() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "Synthetic Alice"));
        when(mapper.findIdempotency("TENANT_A", "SUBMIT_PROMOTED_CASH_ORDER", "gate5b-order-key-0001"))
            .thenReturn(null);
        when(mapper.lockShift("TENANT_A", SHIFT)).thenReturn(new ShiftView(SHIFT, 1101L,
            "01K2A000000000000000000011", 101L, "Synthetic Alice", LocalDate.parse("2026-08-17"),
            "Asia/Shanghai", 1, "OPEN", "CNY", 0, 0, null, null, null, 1));
        when(mapper.addShiftCash("TENANT_A", SHIFT, 900)).thenReturn(1);
        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT)).thenReturn(snapshot(ORDER, HASH_C));
    }

    @Test
    void verifiesPromotionOwnerSnapshotAndWritesOnlyOrderOwnedFacts() {
        SubmitPromotedCashOrder command = command(ORDER, HASH_C);

        var result = service.submit(command);

        assertThat(result.receivableAmountMinor()).isEqualTo(900);
        assertThat(result.changeAmountMinor()).isEqualTo(1100);
        verify(promotions).requireSnapshot("TENANT_A", SNAPSHOT);
        verify(repository).insertOrder(any());
        verify(repository).insertLine(any());
        verify(repository).insertPromotionBinding(any());
        verify(mapper).insertCashPayment(eq("TENANT_A"), any(), eq(ORDER), eq(SHIFT),
            eq(900L), eq(2000L), eq(1100L), eq(900L), any());
        verify(mapper).addShiftCash("TENANT_A", SHIFT, 900);
        verify(mapper).insertIdempotency(eq("TENANT_A"), any(), eq("SUBMIT_PROMOTED_CASH_ORDER"),
            eq("01K5C000000000000000000001"), eq("gate5b-order-key-0001"), any(), eq(ORDER),
            eq("CREATED"), any(), any());
    }

    @Test
    void freezesOriginalMemberBenefitWithoutRepricingOrPii() {
        Snapshot original = snapshot(ORDER, HASH_C);
        MemberBenefit benefit = new MemberBenefit("01K5E000000000000000000001",
            "01K5E000000000000000000002", "MEMBER_PATH",
            "[\"01K5E000000000000000000003\"]", 9, "b".repeat(64), "d".repeat(64),
            "e".repeat(64), "f".repeat(64));
        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT)).thenReturn(new Snapshot(original.snapshotId(),
            original.orderId(), original.quoteId(), original.storeId(), original.terminalId(),
            original.businessDate(), original.currency(), original.quoteFingerprint(),
            original.settlementFingerprint(), original.packageVersion(), original.snapshotSha256(),
            original.grossAmountMinor(), original.discountAmountMinor(), original.payableAmountMinor(),
            original.lines(), benefit));

        service.submit(command(ORDER, HASH_C));

        verify(repository).insertMemberBenefitBinding(argThat(value -> value.selectedPath().equals("MEMBER_PATH")
            && value.entitlementSnapshotId().equals("01K5E000000000000000000001")
            && value.promotionBindingSha256().equals("f".repeat(64))));
    }

    @Test
    void rejectsCrossOrderSnapshotAndHashTamperingBeforeAnyOrderWrite() {
        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT))
            .thenReturn(snapshot("01K5N000000000000000000099", HASH_C));
        assertThatThrownBy(() -> service.submit(command(ORDER, HASH_C)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PROMOTION_SNAPSHOT_MISMATCH");
        verify(repository, never()).insertOrder(any());

        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT)).thenReturn(snapshot(ORDER, HASH_C));
        assertThatThrownBy(() -> service.submit(command(ORDER, "d".repeat(64))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PROMOTION_SNAPSHOT_MISMATCH");
        verify(repository, never()).insertPromotionBinding(any());
    }

    @Test
    void rejectsOrderHeaderOrLineMutationWithoutRunningPromotionAlgorithms() {
        SubmitPromotedCashOrder original = command(ORDER, HASH_C);
        SubmitPromotedCashOrder changed = new SubmitPromotedCashOrder(original.commandId(), original.idempotencyKey(),
            original.orderId(), original.localOrderNo(), original.storeId(), original.terminalId(), original.shiftId(),
            original.cashierId(), original.businessDate(), original.storeTimezone(), original.catalogVersion(),
            original.priceVersion(), original.industryTemplateVersion(), original.promotionSnapshotId(),
            original.promotionSnapshotSha256(), original.quoteFingerprint(), original.settlementFingerprint(),
            original.promotionPackageVersion(), original.orderSnapshotSha256(), original.manualEventRefs(),
            original.grossAmountMinor(), 99, original.surchargeAmountMinor(), 901,
            original.tenderedAmountMinor(), original.lines(), original.occurredAt());
        assertThatThrownBy(() -> service.submit(changed)).hasMessageContaining("ORDER_AMOUNT_CHANGED");
        verify(promotions, never()).requireSnapshot(any(), any());
    }

    @Test
    void rejectsPromotionAllocationSourceReplacementEvenWhenLineTotalsMatch() {
        SubmitPromotedCashOrder original = command(ORDER, HASH_C);
        PromotedLine source = original.lines().get(0);
        PromotedLine changedLine = new PromotedLine(source.lineId(), source.lineNo(), source.skuId(),
            source.skuCode(), source.barcode(), source.productName(), source.unitId(), source.unitCode(),
            source.quantity(), source.unitPriceMinor(), source.grossAmountMinor(), source.discountAmountMinor(),
            source.surchargeAmountMinor(), source.payableAmountMinor(), source.priceSource(),
            Map.of("RULE:TAMPERED", 100L));
        SubmitPromotedCashOrder changed = new SubmitPromotedCashOrder(original.commandId(), original.idempotencyKey(),
            original.orderId(), original.localOrderNo(), original.storeId(), original.terminalId(), original.shiftId(),
            original.cashierId(), original.businessDate(), original.storeTimezone(), original.catalogVersion(),
            original.priceVersion(), original.industryTemplateVersion(), original.promotionSnapshotId(),
            original.promotionSnapshotSha256(), original.quoteFingerprint(), original.settlementFingerprint(),
            original.promotionPackageVersion(), original.orderSnapshotSha256(), original.manualEventRefs(),
            original.grossAmountMinor(), original.discountAmountMinor(), original.surchargeAmountMinor(),
            original.receivableAmountMinor(), original.tenderedAmountMinor(), List.of(changedLine), original.occurredAt());
        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT)).thenReturn(snapshot(ORDER, HASH_C));

        assertThatThrownBy(() -> service.submit(changed))
            .isInstanceOf(ServiceException.class).hasMessageContaining("PROMOTION_SNAPSHOT_MISMATCH");
        verify(repository, never()).insertOrder(any());
    }

    @Test
    void verifiesAndPersistsMeasuredBarcodeSnapshotWithoutRecomputingEncodedAmount() {
        MeasuredBarcodeSnapshot measurement = new MeasuredBarcodeSnapshot("2200123002507", "00123", "00250",
            "0.25", 498, 1990, "CNY", "501", 1, HASH_A, HASH_C, true, NOW);
        PromotedLine line = new PromotedLine(LINE, 1, 101L, "00123", measurement.rawBarcode(),
            "Synthetic Weighted Apple", 201L, "KG", measurement.quantity(), measurement.unitPriceMinor(),
            measurement.amountMinor(), 0, 0, measurement.amountMinor(), "TENANT_BASE", Map.of(), measurement);
        SubmitPromotedCashOrder draft = new SubmitPromotedCashOrder("01K5C000000000000000000002",
            "gate7c-measured-key-001", ORDER, "SYN-G7C-0001", 1101L,
            "01K2A000000000000000000011", SHIFT, "101", LocalDate.parse("2026-08-17"),
            "Asia/Shanghai", 10, 20, "CONVENIENCE_V1", SNAPSHOT, HASH_C, HASH_A, HASH_A,
            1, HASH_A, List.of(), 498, 0, 0, 498, 500, List.of(line), NOW);
        String orderHash = PromotedOrderSnapshotCodec.encode(draft, 101L).sha256();
        SubmitPromotedCashOrder command = new SubmitPromotedCashOrder(draft.commandId(), draft.idempotencyKey(),
            draft.orderId(), draft.localOrderNo(), draft.storeId(), draft.terminalId(), draft.shiftId(),
            draft.cashierId(), draft.businessDate(), draft.storeTimezone(), draft.catalogVersion(),
            draft.priceVersion(), draft.industryTemplateVersion(), draft.promotionSnapshotId(),
            draft.promotionSnapshotSha256(), draft.quoteFingerprint(), draft.settlementFingerprint(),
            draft.promotionPackageVersion(), orderHash, draft.manualEventRefs(), draft.grossAmountMinor(),
            draft.discountAmountMinor(), draft.surchargeAmountMinor(), draft.receivableAmountMinor(),
            draft.tenderedAmountMinor(), draft.lines(), draft.occurredAt());
        when(promotions.requireSnapshot("TENANT_A", SNAPSHOT)).thenReturn(new Snapshot(SNAPSHOT, ORDER, QUOTE,
            1101L, "01K2A000000000000000000011", LocalDate.parse("2026-08-17"), "CNY", HASH_A,
            HASH_A, 1, HASH_C, 498, 0, 498, List.of(new Line(LINE, 1, 101L, new BigDecimal("0.25"),
            498, 0, 498, CanonicalJson.from(Map.of()).sha256()))));
        when(mapper.addShiftCash("TENANT_A", SHIFT, 498)).thenReturn(1);

        service.submit(command);

        verify(weightedBarcodes).verify(eq(1101L), any());
        verify(repository).insertLine(argThat(value -> value.measurementTemplateId().equals(501L)
            && value.measurementTemplateVersion().equals(1)
            && value.measurementSnapshotJson().contains("2200123002507")));
    }

    private SubmitPromotedCashOrder command(String orderId, String promotionHash) {
        PromotedLine line = new PromotedLine(LINE, 1, 101L, "SYN-SKU-101", null, "Synthetic Milk",
            201L, "PCS", "2", 500, 1000, 100, 0, 900, "TENANT_BASE",
            Map.of("RULE:RULE-001", 100L));
        SubmitPromotedCashOrder draft = new SubmitPromotedCashOrder("01K5C000000000000000000001",
            "gate5b-order-key-0001", orderId, "SYN-G5B-0001", 1101L,
            "01K2A000000000000000000011", SHIFT, "101", LocalDate.parse("2026-08-17"),
            "Asia/Shanghai", 10, 20, "CONVENIENCE_V1", SNAPSHOT, promotionHash, HASH_A, HASH_A,
            1, HASH_A, List.of(), 1000, 100, 0, 900, 2000, List.of(line), NOW);
        String orderHash = PromotedOrderSnapshotCodec.encode(draft, 101L).sha256();
        return new SubmitPromotedCashOrder(draft.commandId(), draft.idempotencyKey(), draft.orderId(),
            draft.localOrderNo(), draft.storeId(), draft.terminalId(), draft.shiftId(), draft.cashierId(),
            draft.businessDate(), draft.storeTimezone(), draft.catalogVersion(), draft.priceVersion(),
            draft.industryTemplateVersion(), draft.promotionSnapshotId(), draft.promotionSnapshotSha256(),
            draft.quoteFingerprint(), draft.settlementFingerprint(), draft.promotionPackageVersion(), orderHash,
            draft.manualEventRefs(), draft.grossAmountMinor(), draft.discountAmountMinor(),
            draft.surchargeAmountMinor(), draft.receivableAmountMinor(), draft.tenderedAmountMinor(),
            draft.lines(), draft.occurredAt());
    }

    private Snapshot snapshot(String orderId, String hash) {
        return new Snapshot(SNAPSHOT, orderId, QUOTE, 1101L, "01K2A000000000000000000011",
            LocalDate.parse("2026-08-17"), "CNY", HASH_A, HASH_A, 1, hash,
            1000, 100, 900, List.of(new Line(LINE, 1, 101L, new BigDecimal("2"),
            1000, 100, 900, CanonicalJson.from(Map.of("RULE:RULE-001", 100L)).sha256())));
    }

}
