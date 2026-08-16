package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplyReturn;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.RebuildBalance;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.CommandView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerAggregate;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.PolicyView;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.AnomalyWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryLineSnapshot;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort.InventoryRefundLine;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort.InventoryRefundSnapshot;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

class InventoryLedgerServiceTest {

    private static final String EVENT = "01K2A000000000000000000001";
    private static final String ORDER = "01K2A000000000000000000002";
    private static final String REFUND = "01K2A000000000000000000003";
    private static final String LINE = "01K2A000000000000000000004";
    private static final String WAREHOUSE = "01K2A000000000000000000010";
    private static final String POLICY = "01K2A000000000000000000020";
    private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");

    private final InventoryMapper mapper = mock(InventoryMapper.class);
    private final TrustedTenantContext tenantContext = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final InventoryOrderSnapshotPort orders = mock(InventoryOrderSnapshotPort.class);
    private final InventoryRefundSnapshotPort refunds = mock(InventoryRefundSnapshotPort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private InventoryLedgerService service;

    @BeforeEach
    void setUp() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "alice"));
        when(tenantContext.requireTenantId()).thenReturn("TENANT_A");
        service = new InventoryLedgerService(mapper, tenantContext, authorization, orders, refunds,
            new UlidGenerator(clock), clock, new ObjectMapper());
    }

    @Test
    void appliesSaleAtomicallyFromAuthoritativeSnapshot() {
        saleSnapshot(new BigDecimal("2.000000"));
        effectivePolicy("DENY");
        balance(new BigDecimal("10.000000"));
        when(mapper.completeCommand(any())).thenReturn(1);

        var result = service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace-sale-1"));

        assertThat(result.affectedLines()).isOne();
        assertThat(result.negativeAlert()).isFalse();
        ArgumentCaptor<BalanceUpdate> update = ArgumentCaptor.forClass(BalanceUpdate.class);
        verify(mapper).updateBalance(update.capture());
        assertThat(update.getValue().onHandQuantity()).isEqualByComparingTo("8.000000");
        verify(mapper).insertLedger(any());
        verify(mapper).insertOutbox(any());
        verify(mapper, times(2)).insertAudit(any());
    }

    @Test
    void appliesMultipleLinesInStableOrderAndSingleCommand() {
        String secondLine = "01K2A000000000000000000005";
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 1101L,
            "COMPLETED", "PAID", LocalDate.of(2026, 8, 16), List.of(
                new InventoryLineSnapshot(secondLine, 702L, 301L, new BigDecimal("1.250000")),
                new InventoryLineSnapshot(LINE, 701L, 301L, new BigDecimal("2.000000")))));
        effectivePolicy("DENY");
        when(mapper.lockBalance(any(), any())).thenReturn(
            balanceView(701L, new BigDecimal("10.000000")),
            balanceView(702L, new BigDecimal("5.000000")));
        when(mapper.updateBalance(any())).thenReturn(1);
        when(mapper.completeCommand(any())).thenReturn(1);

        var result = service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace-multiline"));

        assertThat(result.affectedLines()).isEqualTo(2);
        verify(mapper, times(2)).insertLedger(any());
        verify(mapper, times(2)).updateBalance(any());
        verify(mapper, times(2)).insertOutbox(any());
        verify(mapper).completeCommand(any());
    }

    @Test
    void returnsStoredResultForSameEventAndRejectsHashConflict() {
        saleSnapshot(new BigDecimal("1.000000"));
        String matchingHash = requestHashForOneItem();
        when(mapper.findCommand("TENANT_A", EVENT)).thenReturn(new CommandView(EVENT,
            matchingHash, "ORDER", ORDER, "APPLIED", 1, false, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        assertThat(service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace"))).extracting("duplicate")
            .isEqualTo(true);
        verify(mapper, never()).insertLedger(any());

        when(mapper.findCommand("TENANT_A", EVENT)).thenReturn(new CommandView(EVENT,
            "a".repeat(64), "ORDER", ORDER, "APPLIED", 1, false, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        assertThatThrownBy(() -> service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-IDEM-001");
    }

    @Test
    void denyPolicyRejectsNegativeStockBeforeAnyLedgerWrite() {
        saleSnapshot(new BigDecimal("2.000000"));
        effectivePolicy("DENY");
        balance(new BigDecimal("1.000000"));
        assertThatThrownBy(() -> service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-STOCK-001");
        verify(mapper, never()).insertLedger(any());
    }

    @Test
    void allowAndAlertPersistsRealNegativeBalanceAndAnomaly() {
        saleSnapshot(new BigDecimal("2.000000"));
        effectivePolicy("ALLOW_AND_ALERT");
        balance(new BigDecimal("1.000000"));
        when(mapper.completeCommand(any())).thenReturn(1);
        var result = service.applySale(new ApplySale(EVENT, ORDER, WAREHOUSE, "trace"));
        assertThat(result.negativeAlert()).isTrue();
        ArgumentCaptor<AnomalyWrite> anomaly = ArgumentCaptor.forClass(AnomalyWrite.class);
        verify(mapper).insertAnomaly(anomaly.capture());
        assertThat(anomaly.getValue().observedQuantity()).isEqualByComparingTo("-1.000000");
    }

    @Test
    void appliesOnlySucceededReturnAndCrossChecksOriginalLine() {
        InventoryLineSnapshot original = new InventoryLineSnapshot(LINE, 701L, 301L,
            new BigDecimal("2.000000"));
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 1101L,
            "COMPLETED", "PAID", LocalDate.of(2026, 8, 16), List.of(original)));
        when(refunds.requireSnapshot(REFUND)).thenReturn(new InventoryRefundSnapshot(REFUND, ORDER, 1101L,
            "SUCCEEDED", List.of(new InventoryRefundLine(LINE, new BigDecimal("0.500000")))));
        effectivePolicy("DENY");
        balance(new BigDecimal("8.000000"));
        when(mapper.completeCommand(any())).thenReturn(1);
        var result = service.applyReturn(new ApplyReturn(EVENT, REFUND, WAREHOUSE, "trace-return"));
        assertThat(result.affectedLines()).isOne();
        ArgumentCaptor<BalanceUpdate> update = ArgumentCaptor.forClass(BalanceUpdate.class);
        verify(mapper).updateBalance(update.capture());
        assertThat(update.getValue().onHandQuantity()).isEqualByComparingTo("8.500000");
    }

    @Test
    void rejectsUnknownRefundAndExcessReturnQuantity() {
        when(refunds.requireSnapshot(REFUND)).thenReturn(new InventoryRefundSnapshot(REFUND, ORDER, 1101L,
            "UNKNOWN", List.of(new InventoryRefundLine(LINE, BigDecimal.ONE))));
        assertThatThrownBy(() -> service.applyReturn(new ApplyReturn(EVENT, REFUND, WAREHOUSE, "trace")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-SOURCE-002");

        when(refunds.requireSnapshot(REFUND)).thenReturn(new InventoryRefundSnapshot(REFUND, ORDER, 1101L,
            "SUCCEEDED", List.of(new InventoryRefundLine(LINE, new BigDecimal("3")))));
        saleSnapshot(new BigDecimal("2"));
        assertThatThrownBy(() -> service.applyReturn(new ApplyReturn(EVENT, REFUND, WAREHOUSE, "trace")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-REFUND-004");
    }

    @Test
    void rebuildsProjectionOnlyFromLedgerAggregate() {
        effectiveWarehousePolicy();
        String dimension = com.jingshanghui.pos.inventory.domain.InventoryHash.dimension("TENANT_A", WAREHOUSE, 701L);
        when(mapper.lockBalance("TENANT_A", dimension)).thenReturn(balanceView(new BigDecimal("9.000000")));
        when(mapper.aggregateLedger("TENANT_A", dimension))
            .thenReturn(new LedgerAggregate(new BigDecimal("10.000000"), 4, 4));
        when(mapper.rebuildBalance(any())).thenReturn(1);
        var result = service.rebuild(new RebuildBalance(WAREHOUSE, 701L, "trace-rebuild"));
        assertThat(result.changed()).isTrue();
        assertThat(result.ledgerQuantity()).isEqualByComparingTo("10.000000");
        verify(mapper).rebuildBalance(any());
    }

    private void saleSnapshot(BigDecimal quantity) {
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 1101L,
            "COMPLETED", "PAID", LocalDate.of(2026, 8, 16),
            List.of(new InventoryLineSnapshot(LINE, 701L, 301L, quantity))));
    }

    private void effectivePolicy(String mode) {
        when(mapper.findEffectivePolicy(any(), any(), any(), any())).thenReturn(new PolicyView(POLICY, 1101L,
            WAREHOUSE, mode, LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC)));
    }

    private void effectiveWarehousePolicy() {
        when(mapper.findEffectivePolicyByWarehouse(any(), any(), any())).thenReturn(new PolicyView(POLICY, 1101L,
            WAREHOUSE, "DENY", LocalDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC)));
    }

    private void balance(BigDecimal quantity) {
        when(mapper.lockBalance(any(), any())).thenReturn(balanceView(quantity));
        when(mapper.updateBalance(any())).thenReturn(1);
    }

    private BalanceView balanceView(BigDecimal quantity) {
        return balanceView(701L, quantity);
    }

    private BalanceView balanceView(Long skuId, BigDecimal quantity) {
        return new BalanceView("d".repeat(64), WAREHOUSE, skuId, "SALEABLE", quantity,
            new BigDecimal("0.000000"), new BigDecimal("0.000000"), new BigDecimal("0.000000"), 3, 3);
    }

    private String requestHashForOneItem() {
        var command = new ApplySale(EVENT, ORDER, WAREHOUSE, "trace");
        var order = orders.requireSnapshot(ORDER);
        List<Object> values = new java.util.ArrayList<>(List.of(command.eventId(), order.orderId(),
            command.warehouseId(), order.storeId(), order.status(), order.paymentStatus(), order.businessDate()));
        var line = order.lines().get(0);
        values.add(line.orderLineId()); values.add(line.skuId()); values.add(line.unitId());
        values.add(line.quantity().setScale(6).toPlainString());
        return com.jingshanghui.pos.inventory.domain.InventoryHash.sha256(
            com.jingshanghui.pos.inventory.domain.InventoryHash.canonical(values));
    }
}
