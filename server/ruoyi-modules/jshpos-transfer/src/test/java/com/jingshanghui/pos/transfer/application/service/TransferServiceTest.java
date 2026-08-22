package com.jingshanghui.pos.transfer.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.DispatchTransfer;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.CreateLine;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.CreateTransfer;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.DifferenceLine;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.ReceiveLine;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.ReceiveTransfer;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.ResolveDifference;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.StateCommand;
import com.jingshanghui.pos.transfer.application.model.TransferViews.TransferHead;
import com.jingshanghui.pos.transfer.application.model.TransferViews.TransferLine;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.DispatchWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.CommandWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.OrderWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.LineWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.StatusUpdate;
import com.jingshanghui.pos.transfer.infrastructure.persistence.TransferPersistenceParams.TransitWrite;
import com.jingshanghui.pos.transfer.infrastructure.persistence.mapper.TransferMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferServiceTest {
    private static final String TRANSFER = "01K2A000000000000000000001";
    private static final String LINE = "01K2A000000000000000000002";
    private static final String DISPATCH = "01K2A000000000000000000003";
    private static final String EVENT = "01K2A000000000000000000004";
    private static final String SOURCE_WH = "01K2A000000000000000000010";
    private static final String DEST_WH = "01K2A000000000000000000011";
    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private final TransferMapper mapper = mock(TransferMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final InventoryCatalogSnapshotPort catalog = mock(InventoryCatalogSnapshotPort.class);
    private final AuthoritativeInventoryMovementPort movement = mock(AuthoritativeInventoryMovementPort.class);
    private final com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort lots =
        mock(com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort.class);
    private final StoreService stores = mock(StoreService.class);
    private TransferService service;

    @BeforeEach
    void setUp() {
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "alice"));
        when(context.requireTenantId()).thenReturn("TENANT_A");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new TransferService(mapper, context, authorization, catalog, movement, lots, stores,
            new UlidGenerator(clock), new ObjectMapper().findAndRegisterModules(), clock);
    }

    @Test
    void dispatchPersistsAuthoritativeFactBeforeCallingInventoryOwner() throws Exception {
        TransferHead head = head("APPROVED", 2, 100L, 102L);
        TransferLine line = line("10", "0", "0", "0");
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(head);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(head);
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line));
        when(mapper.updateLineProgress(any())).thenReturn(1);
        when(mapper.updateStatus(any())).thenReturn(1);
        when(mapper.markCommandApplied(any())).thenReturn(1);
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        when(movement.applyOwnedMovement(any())).thenReturn(new ApplyResult(EVENT, "TRANSFER_DISPATCH",
            DISPATCH, 1, false, false));
        when(lots.requiresLotTracking(1101L, 701L, LocalDate.of(2026, 8, 17))).thenReturn(true);

        service.dispatch(new DispatchTransfer(TRANSFER, DISPATCH, EVENT, 2, "trace-dispatch", List.of(
            new com.jingshanghui.pos.transfer.application.model.TransferCommands.DispatchLotSplit(
                LINE, "01K2A000000000000000000180", new BigDecimal("10")))));

        InOrder order = inOrder(mapper, movement);
        order.verify(mapper).insertDispatch(any(DispatchWrite.class));
        order.verify(mapper).insertDispatchLine(any());
        order.verify(movement).applyOwnedMovement(any());
        ArgumentCaptor<OwnedMovement> fact = ArgumentCaptor.forClass(OwnedMovement.class);
        verify(movement).applyOwnedMovement(fact.capture());
        assertThat(fact.getValue().sourceType()).isEqualTo("TRANSFER_DISPATCH");
        assertThat(fact.getValue().warehouseId()).isEqualTo(SOURCE_WH);
        assertThat(fact.getValue().lines()).singleElement().satisfies(value -> {
            assertThat(value.movementType()).isEqualTo(MovementType.TRANSFER_OUT);
            assertThat(value.quantity()).isEqualByComparingTo("10.000000");
        });
        verify(authorization, times(2)).requireStoreAccess(1101L);
        verify(authorization, times(2)).requireStoreAccess(1102L);
        verify(lots).applyExplicit(any());
        ArgumentCaptor<OutboxWrite> outbox = ArgumentCaptor.forClass(OutboxWrite.class);
        verify(mapper).insertOutbox(outbox.capture());
        var payload = new ObjectMapper().findAndRegisterModules().readTree(outbox.getValue().payloadJson());
        assertThat(payload.path("eventId").asText()).isEqualTo(outbox.getValue().eventId());
        assertThat(payload.path("eventType").asText()).isEqualTo("inventory.transfer.dispatched.v1");
        assertThat(payload.path("aggregateVersion").asLong()).isEqualTo(3);
        assertThat(payload.path("businessDate").asText()).isEqualTo("2026-08-17");
        assertThat(payload.has("tenantId")).isFalse();
    }

    @Test
    void creatorCannotApproveOwnTransfer() {
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(head("SUBMITTED", 1, 101L, null));
        assertThatThrownBy(() -> service.approve(new StateCommand(TRANSFER, EVENT, 1,
            "approve", "trace-approve"))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("TRF-APPROVAL-001");
        verify(mapper, never()).updateStatus(any());
        verify(movement, never()).applyOwnedMovement(any());
    }

    @Test
    void rejectsDuplicateSkuAndSameWarehouseAtCreation() {
        CreateLine first = new CreateLine(LINE, 701L, 301L, BigDecimal.ONE);
        CreateLine duplicate = new CreateLine("01K2A000000000000000000012", 701L, 301L, BigDecimal.ONE);
        assertThatThrownBy(() -> service.create(new CreateTransfer(TRANSFER, 1101L, SOURCE_WH, 1102L,
            DEST_WH, List.of(first, duplicate), "restock", "trace-create")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-INPUT-003");
        assertThatThrownBy(() -> service.create(new CreateTransfer(TRANSFER, 1101L, SOURCE_WH, 1102L,
            SOURCE_WH, List.of(first), "restock", "trace-create")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-ROUTE-001");
    }

    @Test
    void concurrentDuplicateCreateWithSameDigestReturnsCommittedAggregate() {
        AtomicReference<String> insertedHash = new AtomicReference<>();
        TransferHead committed = head("DRAFT", 0, 101L, null);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(null, committed);
        doAnswer(invocation -> {
            insertedHash.set(invocation.getArgument(0, OrderWrite.class).requestSha256());
            throw new DuplicateKeyException("synthetic concurrent insert");
        }).when(mapper).insertOrder(any());
        when(mapper.findOrderRequestHash("TENANT_A", TRANSFER)).thenAnswer(ignored -> insertedHash.get());
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of());

        var result = service.create(new CreateTransfer(TRANSFER, 1101L, SOURCE_WH, 1102L, DEST_WH,
            List.of(new CreateLine(LINE, 701L, 301L, BigDecimal.ONE)), "restock", "trace-create"));

        assertThat(result.head()).isEqualTo(committed);
        verify(mapper, never()).insertLine(any());
        verify(mapper, never()).insertAudit(any());
        verify(mapper, never()).insertOutbox(any());
    }

    @Test
    void createFreezesOriginalUnitConversionAndBaseQuantity() {
        TransferHead draft = head("DRAFT", 0, 101L, null);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(null, draft);
        when(catalog.requireUnit(701L, 302L)).thenReturn(
            new SkuUnitSnapshot(701L, "A-SKU", 302L, 301L, 12, 1, false));
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(
            new TransferLine(LINE, TRANSFER, 701L, 302L, 12, 1, new BigDecimal("2.000000"),
                301L, new BigDecimal("24.000000"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

        service.create(new CreateTransfer(TRANSFER, 1101L, SOURCE_WH, 1102L, DEST_WH,
            List.of(new CreateLine(LINE, 701L, 302L, new BigDecimal("2"))), "restock", "trace-create"));

        ArgumentCaptor<LineWrite> written = ArgumentCaptor.forClass(LineWrite.class);
        verify(mapper).insertLine(written.capture());
        assertThat(written.getValue().requestedUnitId()).isEqualTo(302L);
        assertThat(written.getValue().conversionNumerator()).isEqualTo(12);
        assertThat(written.getValue().conversionDenominator()).isEqualTo(1);
        assertThat(written.getValue().inputQuantity()).isEqualByComparingTo("2.000000");
        assertThat(written.getValue().requestedQuantity()).isEqualByComparingTo("24.000000");
        verify(mapper).insertOutbox(any());
    }

    @Test
    void submitsWithOptimisticVersionAndCancelsOnlyBeforeDispatch() {
        TransferHead draft = head("DRAFT", 0, 100L, null);
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(draft);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(draft);
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line("10", "0", "0", "0")));
        when(mapper.updateStatus(any())).thenReturn(1);
        when(mapper.markCommandApplied(any())).thenReturn(1);
        service.submit(new StateCommand(TRANSFER, EVENT, 0, "submit", "trace-submit"));
        ArgumentCaptor<StatusUpdate> update = ArgumentCaptor.forClass(StatusUpdate.class);
        verify(mapper).updateStatus(update.capture());
        assertThat(update.getValue().nextStatus()).isEqualTo("SUBMITTED");

        TransferMapper postMapper = mock(TransferMapper.class);
        when(postMapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(head("IN_TRANSIT", 3, 100L, 102L));
        TransferService postService = new TransferService(postMapper, context, authorization, catalog, movement,
            lots, stores, new UlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)), new ObjectMapper().findAndRegisterModules(),
            Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> postService.cancel(new StateCommand(TRANSFER,
            "01K2A000000000000000000020", 3, "cancel", "trace-cancel")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-CANCEL-001");
        verify(postMapper, never()).insertCommand(any());
    }

    @Test
    void duplicateDispatchReturnsOriginalAndConflictingDigestFails() {
        prepareDispatch();
        DispatchTransfer first = new DispatchTransfer(TRANSFER, DISPATCH, EVENT, 2, "trace-1");
        service.dispatch(first);
        ArgumentCaptor<CommandWrite> command = ArgumentCaptor.forClass(CommandWrite.class);
        verify(mapper).insertCommand(command.capture());
        when(mapper.findCommandHash("TENANT_A", EVENT)).thenReturn(command.getValue().requestSha256());
        when(mapper.findCommandStatus("TENANT_A", EVENT)).thenReturn("APPLIED");
        service.dispatch(new DispatchTransfer(TRANSFER, DISPATCH, EVENT, 2, "trace-retry"));
        assertThatThrownBy(() -> service.dispatch(new DispatchTransfer(TRANSFER,
            "01K2A000000000000000000030", EVENT, 2, "trace-conflict")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("TRF-IDEM-002");
        verify(movement, times(1)).applyOwnedMovement(any());
    }

    @Test
    void inventoryFailurePropagatesBeforeCommandCompletionForAtomicRollback() {
        prepareDispatch();
        when(movement.applyOwnedMovement(any())).thenThrow(new ServiceException("synthetic cost failure", 409));
        assertThatThrownBy(() -> service.dispatch(new DispatchTransfer(TRANSFER, DISPATCH, EVENT, 2,
            "trace-fail"))).isInstanceOf(ServiceException.class).hasMessageContaining("synthetic cost failure");
        verify(mapper, never()).markCommandApplied(any());
        verify(mapper, never()).insertOutbox(any());
    }

    @Test
    void partialAndFinalReceiptsConvergeWithoutOverReceipt() {
        String receipt1 = "01K2A000000000000000000040";
        String event1 = "01K2A000000000000000000041";
        String receiptLine1 = "01K2A000000000000000000042";
        String receipt2 = "01K2A000000000000000000043";
        String event2 = "01K2A000000000000000000044";
        String receiptLine2 = "01K2A000000000000000000045";
        var dispatchLine = new com.jingshanghui.pos.transfer.application.model.TransferViews.DispatchLine(
            "01K2A000000000000000000046", DISPATCH, LINE, 701L, 301L, new BigDecimal("10.000000"));
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(
            head("IN_TRANSIT", 3, 100L, 102L), head("PARTIALLY_RECEIVED", 4, 100L, 102L));
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(head("PARTIALLY_RECEIVED", 4, 100L, 102L),
            head("CLOSED", 5, 100L, 102L));
        when(mapper.lockLine("TENANT_A", TRANSFER, LINE)).thenReturn(line("10", "10", "0", "0"),
            line("10", "10", "4", "0"));
        when(mapper.findDispatchLines("TENANT_A", TRANSFER)).thenReturn(List.of(dispatchLine));
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line("10", "10", "4", "0")),
            List.of(line("10", "10", "4", "0")), List.of(line("10", "10", "10", "0")));
        when(mapper.updateLineProgress(any())).thenReturn(1);
        when(mapper.updateStatus(any())).thenReturn(1);
        when(mapper.markCommandApplied(any())).thenReturn(1);
        when(stores.businessDate(1102L, NOW)).thenReturn(new BusinessDateView(1102L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));

        service.receive(new ReceiveTransfer(TRANSFER, receipt1, event1, 3, false,
            List.of(new ReceiveLine(receiptLine1, LINE, new BigDecimal("4"))), "trace-r1"));
        service.receive(new ReceiveTransfer(TRANSFER, receipt2, event2, 4, true,
            List.of(new ReceiveLine(receiptLine2, LINE, new BigDecimal("6"))), "trace-r2"));

        ArgumentCaptor<StatusUpdate> states = ArgumentCaptor.forClass(StatusUpdate.class);
        verify(mapper, times(2)).updateStatus(states.capture());
        assertThat(states.getAllValues()).extracting(StatusUpdate::nextStatus)
            .containsExactly("PARTIALLY_RECEIVED", "CLOSED");
        ArgumentCaptor<OwnedMovement> facts = ArgumentCaptor.forClass(OwnedMovement.class);
        verify(movement, times(2)).applyOwnedMovement(facts.capture());
        assertThat(facts.getAllValues()).allSatisfy(value -> {
            assertThat(value.sourceType()).isEqualTo("TRANSFER_RECEIPT");
            assertThat(value.warehouseId()).isEqualTo(DEST_WH);
            assertThat(value.lines().get(0).movementType()).isEqualTo(MovementType.TRANSFER_IN);
        });
    }

    @Test
    void finalShortReceiptRequiresExactDifference() {
        String commandId = "01K2A000000000000000000050";
        TransferHead pending = head("DIFFERENCE_PENDING", 4, 100L, 102L);
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(pending);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(pending);
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line("10", "10", "4", "0")));
        when(mapper.updateLineProgress(any())).thenReturn(1);
        when(mapper.updateStatus(any())).thenReturn(1);
        when(mapper.markCommandApplied(any())).thenReturn(1);
        when(stores.businessDate(1102L, NOW)).thenReturn(new BusinessDateView(1102L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));

        service.resolveDifference(new ResolveDifference(TRANSFER, commandId, 4,
            List.of(new DifferenceLine(LINE, new BigDecimal("6"), "SHORTAGE")),
            "carrier shortage", "trace-diff"));
        ArgumentCaptor<StatusUpdate> state = ArgumentCaptor.forClass(StatusUpdate.class);
        verify(mapper).updateStatus(state.capture());
        assertThat(state.getValue().nextStatus()).isEqualTo("CLOSED");
        ArgumentCaptor<TransitWrite> transit = ArgumentCaptor.forClass(TransitWrite.class);
        verify(mapper).insertTransit(transit.capture());
        assertThat(transit.getValue().factType()).isEqualTo("DIFFERENCE_APPROVED");
        assertThat(transit.getValue().reasonCode()).isEqualTo("SHORTAGE");
    }

    @Test
    void reconcilesOnlineProjectionFromImmutableTransitLedgerWithoutMutation() {
        TransferHead closed = head("CLOSED", 5, 100L, 102L);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(closed);
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line("10", "10", "4", "6")));
        when(mapper.sumTransit("TENANT_A", LINE, "DISPATCHED")).thenReturn(new BigDecimal("10"));
        when(mapper.sumTransit("TENANT_A", LINE, "RECEIVED")).thenReturn(new BigDecimal("4"));
        when(mapper.sumTransit("TENANT_A", LINE, "DIFFERENCE_APPROVED")).thenReturn(new BigDecimal("6"));

        var result = service.reconcileTransit(TRANSFER);

        assertThat(result.consistent()).isTrue();
        assertThat(result.lines()).singleElement().satisfies(line -> {
            assertThat(line.openTransitQuantity()).isEqualByComparingTo("0.000000");
            assertThat(line.consistent()).isTrue();
        });
        verify(mapper, never()).updateLineProgress(any());
        verify(mapper, never()).insertTransit(any());
    }

    private void prepareDispatch() {
        TransferHead head = head("APPROVED", 2, 100L, 102L);
        when(mapper.lockOrder("TENANT_A", TRANSFER)).thenReturn(head);
        when(mapper.findOrder("TENANT_A", TRANSFER)).thenReturn(head);
        when(mapper.findLines("TENANT_A", TRANSFER)).thenReturn(List.of(line("10", "0", "0", "0")));
        when(mapper.updateLineProgress(any())).thenReturn(1);
        when(mapper.updateStatus(any())).thenReturn(1);
        when(mapper.markCommandApplied(any())).thenReturn(1);
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        when(movement.applyOwnedMovement(any())).thenReturn(new ApplyResult(EVENT, "TRANSFER_DISPATCH",
            DISPATCH, 1, false, false));
    }

    private TransferHead head(String status, long version, Long creator, Long approver) {
        return new TransferHead(TRANSFER, 1101L, SOURCE_WH, 1102L, DEST_WH, status, "restock",
            creator, approver, null, null, null, version);
    }

    private TransferLine line(String requested, String dispatched, String received, String difference) {
        return new TransferLine(LINE, TRANSFER, 701L, 301L, 1, 1,
            new BigDecimal(requested).setScale(6), 301L, new BigDecimal(requested).setScale(6),
            new BigDecimal(dispatched).setScale(6), new BigDecimal(received).setScale(6),
            new BigDecimal(difference).setScale(6));
    }
}
