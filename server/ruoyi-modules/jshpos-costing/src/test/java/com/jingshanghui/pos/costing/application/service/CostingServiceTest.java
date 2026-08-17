package com.jingshanghui.pos.costing.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.costing.application.model.CostingCommands.PublishPolicy;
import com.jingshanghui.pos.costing.application.model.CostingCommands.RebuildBalance;
import com.jingshanghui.pos.costing.application.model.CostingViews.BalanceView;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerAggregate;
import com.jingshanghui.pos.costing.application.model.CostingViews.LedgerView;
import com.jingshanghui.pos.costing.application.model.CostingViews.PolicyView;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.CostingPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.costing.infrastructure.persistence.mapper.CostingMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort.PostedInventoryLedger;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.DispatchCostSource;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CostingServiceTest {

    private static final String INVENTORY = "01K2A000000000000000000001";
    private static final String WAREHOUSE = "01K2A000000000000000000010";
    private static final String SOURCE = "01K2A000000000000000000002";
    private static final String LINE = "01K2A000000000000000000003";
    private static final String EVENT = "01K2A000000000000000000004";
    private static final String POLICY = "01K2A000000000000000000005";
    private static final String DIMENSION = "a".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final CostingMapper mapper = mock(CostingMapper.class);
    private final TrustedTenantContext tenantContext = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final ProcurementCostSourcePort procurement = mock(ProcurementCostSourcePort.class);
    private final TransferCostSourcePort transfer = mock(TransferCostSourcePort.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private CostingService service;

    @BeforeEach
    void setUp() {
        when(tenantContext.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "alice"));
        when(tenantContext.requireTenantId()).thenReturn("TENANT_A");
        service = new CostingService(mapper, tenantContext, authorization, procurement, transfer,
            new UlidGenerator(clock), clock, new ObjectMapper());
    }

    @Test
    void postsConfirmedReceiptAsImmutableCostFactAndReturnsDuplicateWithoutSecondEffect() throws Exception {
        PostedInventoryLedger fact = receipt(1, "0", "1", "trace-cost-1");
        receiptSource("1");
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.lockBalance(any(), any())).thenReturn(balance("0", "0", 0, 0));
        when(mapper.updateBalance(any())).thenReturn(1);

        var result = service.applyPostedLedger(fact);

        assertThat(result.duplicate()).isFalse();
        ArgumentCaptor<LedgerWrite> write = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(mapper).insertLedger(write.capture());
        assertThat(write.getValue().costAmountAfterMinor()).isEqualByComparingTo("100.000000");
        assertThat(write.getValue().valuationMethod()).isEqualTo("PURCHASE_FROZEN_PRICE");
        ArgumentCaptor<OutboxWrite> outbox = ArgumentCaptor.forClass(OutboxWrite.class);
        verify(mapper).insertOutbox(outbox.capture());
        var event = new ObjectMapper().readTree(outbox.getValue().payloadJson());
        assertThat(event.get("inventoryLedgerSequence").asLong()).isOne();
        assertThat(event.get("movementType").asText()).isEqualTo("PURCHASE_RECEIPT_IN");
        assertThat(event.get("avgUnitCostAfterMinor").asText()).isEqualTo("100.000000");
        assertThat(event.get("policyVersionId").asText()).isEqualTo(POLICY);
        when(mapper.findLedgerByInventory("TENANT_A", INVENTORY)).thenReturn(view(write.getValue()));

        var duplicate = service.applyPostedLedger(fact);

        assertThat(duplicate.duplicate()).isTrue();
        verify(mapper, times(1)).insertLedger(any());
        verify(mapper, times(1)).updateBalance(any());
    }

    @Test
    void rejectsSameInventoryIdWithChangedContent() {
        receiptSource("1");
        LedgerWrite prior = ledgerWrite("b".repeat(64));
        when(mapper.findLedgerByInventory("TENANT_A", INVENTORY)).thenReturn(view(prior));

        assertThatThrownBy(() -> service.applyPostedLedger(receipt(1, "0", "1", "trace-changed")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-IDEM-CONFLICT");
    }

    @Test
    void rejectsSequenceGapAndQuantityDivergenceFromInventoryOwner() {
        receiptSource("1");
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.lockBalance(any(), any())).thenReturn(balance("2", "200", 2, 2));

        assertThatThrownBy(() -> service.applyPostedLedger(receipt(4, "2", "3", "trace-gap")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-SEQUENCE-GAP");

        when(mapper.lockBalance(any(), any())).thenReturn(balance("5", "500", 0, 0));
        assertThatThrownBy(() -> service.applyPostedLedger(receipt(1, "0", "1", "trace-diverge")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-QTY-MISMATCH");
    }

    @Test
    void rejectsUnknownLateSequenceAndOptimisticConcurrentConflict() {
        receiptSource("1");
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.lockBalance(any(), any())).thenReturn(balance("2", "200", 2, 2));
        assertThatThrownBy(() -> service.applyPostedLedger(receipt(1, "2", "3", "trace-late")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-LATE-REQUIRES-REBUILD");

        when(mapper.lockBalance(any(), any())).thenReturn(balance("0", "0", 0, 0));
        when(mapper.updateBalance(any())).thenReturn(0);
        assertThatThrownBy(() -> service.applyPostedLedger(receipt(1, "0", "1", "trace-concurrent")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-BALANCE-002");
    }

    @Test
    void rejectsMixedCurrencyFromProcurementOwner() {
        when(procurement.requireReceiptLine(LINE)).thenReturn(new ReceiptCostSource(LINE, SOURCE,
            "01K2A000000000000000000006", 701L, 301L, BigDecimal.ONE, 100, 1, 1, "USD"));
        assertThatThrownBy(() -> service.applyPostedLedger(receipt(1, "0", "1", "trace-currency")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-SOURCE-MISMATCH");
    }

    @Test
    void validatesTransferOwnerAndFreezesSourceWarehouseCostSnapshot() {
        when(transfer.requireDispatchLine(LINE)).thenReturn(new DispatchCostSource(LINE, SOURCE,
            "01K2A000000000000000000007", WAREHOUSE, "01K2A000000000000000000011",
            701L, 301L, BigDecimal.ONE, "CNY"));
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.lockBalance(any(), any())).thenReturn(balance("2", "200", 0, 0));
        when(mapper.updateBalance(any())).thenReturn(1);

        service.applyPostedLedger(transferFact("TRANSFER_OUT", "TRANSFER_DISPATCH", "2", "-1", "1"));

        ArgumentCaptor<LedgerWrite> write = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(mapper).insertLedger(write.capture());
        assertThat(write.getValue().unitCostMinor()).isEqualByComparingTo("100.000000");
        assertThat(write.getValue().valuationMethod()).isEqualTo("TRANSFER_SOURCE_SNAPSHOT");
    }

    @Test
    void rejectsTransferReceiptWithoutOriginalDispatchCostSnapshot() {
        String sourceWarehouse = "01K2A000000000000000000011";
        when(transfer.requireReceiptLine(LINE)).thenReturn(new TransferCostSourcePort.ReceiptCostSource(
            LINE, SOURCE, "01K2A000000000000000000008", sourceWarehouse, WAREHOUSE,
            701L, 301L, BigDecimal.ONE, "CNY"));
        assertThatThrownBy(() -> service.applyPostedLedger(
            transferFact("TRANSFER_IN", "TRANSFER_RECEIPT", "0", "1", "1")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("调拨收货缺少来源仓发出成本快照");
        verify(mapper).findSourceLedger("TENANT_A", sourceWarehouse, 701L,
            "TRANSFER_DISPATCH", "01K2A000000000000000000008", "TRANSFER_OUT");
    }

    @Test
    void partialTransferReceiptInheritsOriginalDispatchCost() {
        String sourceWarehouse = "01K2A000000000000000000011";
        String dispatchLine = "01K2A000000000000000000008";
        when(transfer.requireReceiptLine(LINE)).thenReturn(new TransferCostSourcePort.ReceiptCostSource(
            LINE, SOURCE, dispatchLine, sourceWarehouse, WAREHOUSE,
            701L, 301L, BigDecimal.ONE, "CNY"));
        when(mapper.findSourceLedger("TENANT_A", sourceWarehouse, 701L,
            "TRANSFER_DISPATCH", dispatchLine, "TRANSFER_OUT")).thenReturn(view(ledgerWrite("a".repeat(64))));
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.lockBalance(any(), any())).thenReturn(balance("2", "220", 0, 0));
        when(mapper.updateBalance(any())).thenReturn(1);

        service.applyPostedLedger(transferFact("TRANSFER_IN", "TRANSFER_RECEIPT", "2", "1", "3"));

        ArgumentCaptor<LedgerWrite> write = ArgumentCaptor.forClass(LedgerWrite.class);
        verify(mapper).insertLedger(write.capture());
        assertThat(write.getValue().unitCostMinor()).isEqualByComparingTo("100.000000");
        assertThat(write.getValue().valuationMethod()).isEqualTo("INHERITED_TRANSFER_COST");
        assertThat(write.getValue().averageUnitCostAfterMinor()).isEqualByComparingTo("106.666667");
    }

    @Test
    void publishesFrozenPolicyAndRebuildsProjectionFromLedgerOnly() {
        var published = service.publishPolicy(new PublishPolicy(POLICY, 1101L, WAREHOUSE, NOW, "trace-policy"));
        assertThat(published.currencyCode()).isEqualTo("CNY");
        verify(mapper).insertPolicy(any());

        BalanceView balance = balance("3", "330", 3, 3);
        when(mapper.findBalance("TENANT_A", WAREHOUSE, 701L)).thenReturn(balance);
        when(mapper.lockBalance("TENANT_A", balance.costDimensionKey())).thenReturn(balance);
        when(mapper.aggregateLedger("TENANT_A", balance.costDimensionKey()))
            .thenReturn(new LedgerAggregate(new BigDecimal("3.000000"), new BigDecimal("300.000000"),
                3, 3, 3, new BigDecimal("100.000000")));
        when(mapper.findEffectivePolicy(any(), any(), any())).thenReturn(policy());
        when(mapper.rebuildBalance(any())).thenReturn(1);

        var rebuilt = service.rebuild(new RebuildBalance("01K2A000000000000000000099", WAREHOUSE,
            701L, "trace-rebuild"));

        assertThat(rebuilt.changed()).isTrue();
        ArgumentCaptor<BalanceUpdate> update = ArgumentCaptor.forClass(BalanceUpdate.class);
        verify(mapper).rebuildBalance(update.capture());
        assertThat(update.getValue().costAmountMinor()).isEqualByComparingTo("300.000000");
        verify(mapper).insertRebuild(any());
    }

    @Test
    void trustedTenantControlsAllReads() {
        when(mapper.findBalance("TENANT_A", WAREHOUSE, 701L)).thenReturn(balance("1", "100", 1, 1));
        service.findBalance(WAREHOUSE, 701L);
        service.findLedger(WAREHOUSE, 701L, 0, 100);
        verify(mapper, times(2)).findBalance("TENANT_A", WAREHOUSE, 701L);
        verify(mapper).findLedger("TENANT_A", DIMENSION, 0, 100);
    }

    private void receiptSource(String quantity) {
        when(procurement.requireReceiptLine(LINE)).thenReturn(new ReceiptCostSource(LINE, SOURCE,
            "01K2A000000000000000000006", 701L, 301L, new BigDecimal(quantity), 100, 1, 1, "CNY"));
    }

    private PostedInventoryLedger receipt(long sequence, String before, String after, String correlation) {
        return new PostedInventoryLedger(INVENTORY, sequence,
            InventoryHash.dimension("TENANT_A", WAREHOUSE, 701L), WAREHOUSE, 1101L, 701L, 301L,
            "PURCHASE_RECEIPT_IN", new BigDecimal(before), BigDecimal.ONE,
            new BigDecimal(after), "PURCHASE_RECEIPT", SOURCE, LINE, EVENT, null,
            LocalDate.of(2026, 8, 17), correlation, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private PostedInventoryLedger transferFact(String movementType, String sourceType,
                                                String before, String delta, String after) {
        return new PostedInventoryLedger(INVENTORY, 1,
            InventoryHash.dimension("TENANT_A", WAREHOUSE, 701L), WAREHOUSE, 1101L, 701L, 301L,
            movementType, new BigDecimal(before), new BigDecimal(delta), new BigDecimal(after),
            sourceType, SOURCE, LINE, EVENT, null, LocalDate.of(2026, 8, 17),
            "trace-transfer-cost", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private PolicyView policy() {
        return new PolicyView(POLICY, 1101L, WAREHOUSE, "WAREHOUSE", "CNY", 6, 6,
            "HALF_EVEN", "ZERO_AMOUNT_KEEP_LAST_UNIT_COST", LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private BalanceView balance(String quantity, String amount, long costSeq, long inventorySeq) {
        BigDecimal q = new BigDecimal(quantity).setScale(6);
        BigDecimal a = new BigDecimal(amount).setScale(6);
        BigDecimal average = q.signum() == 0 ? BigDecimal.ZERO.setScale(6) : a.divide(q, 6, java.math.RoundingMode.HALF_EVEN);
        return new BalanceView(DIMENSION, WAREHOUSE, WAREHOUSE, 1101L, 701L, "CNY", q, a,
            average, average, costSeq, inventorySeq, 0);
    }

    private LedgerWrite ledgerWrite(String sourceHash) {
        return new LedgerWrite("01K2A000000000000000000090", "TENANT_A", DIMENSION, WAREHOUSE, 1,
            INVENTORY, 1, WAREHOUSE, 701L, "CNY", "PURCHASE_RECEIPT_IN",
            BigDecimal.ZERO.setScale(6), BigDecimal.ONE.setScale(6), BigDecimal.ONE.setScale(6),
            BigDecimal.ZERO.setScale(6), new BigDecimal("100.000000"), new BigDecimal("100.000000"),
            new BigDecimal("100.000000"), new BigDecimal("100.000000"), "PURCHASE_FROZEN_PRICE", false,
            BigDecimal.ZERO.setScale(6), "PURCHASE_RECEIPT", SOURCE, LINE, EVENT, sourceHash, POLICY,
            null, LocalDate.of(2026, 8, 17), 101L, "trace-cost-1",
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private LedgerView view(LedgerWrite write) {
        return new LedgerView(write.costLedgerId(), write.costLedgerSequence(), write.inventoryLedgerId(),
            write.inventoryLedgerSequence(), write.warehouseId(), write.skuId(), write.currencyCode(),
            write.movementType(), write.quantityBefore(), write.quantityDelta(), write.quantityAfter(),
            write.costAmountBeforeMinor(), write.costAmountDeltaMinor(), write.costAmountAfterMinor(),
            write.unitCostMinor(), write.averageUnitCostAfterMinor(), write.valuationMethod(),
            write.costEstimated(), write.varianceAmountMinor(), write.sourceType(), write.sourceId(),
            write.sourceLineId(), write.sourceSha256(), write.policyVersionId(), write.reversalOfCostLedgerId(),
            write.businessDate(), write.occurredAt());
    }
}
