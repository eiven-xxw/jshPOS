package com.jingshanghui.pos.procurement.application.service;

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
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ConfirmReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.SubmitOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnHead;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ProcurementMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证采购草稿/确认、职责分离及库存 Owner 原子调用边界。 */
class ProcurementServiceTest {

    private static final String ORDER = "01K2A000000000000000000101";
    private static final String ORDER_LINE = "01K2A000000000000000000102";
    private static final String RECEIPT = "01K2A000000000000000000103";
    private static final String RECEIPT_LINE = "01K2A000000000000000000104";
    private static final String RETURN = "01K2A000000000000000000105";
    private static final String RETURN_LINE = "01K2A000000000000000000106";
    private static final String EVENT = "01K2A000000000000000000107";
    private static final String WAREHOUSE = "01K2A000000000000000000010";
    private static final Instant NOW = Instant.parse("2026-08-17T01:00:00Z");
    private final ProcurementMapper mapper = mock(ProcurementMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final InventoryCatalogSnapshotPort catalog = mock(InventoryCatalogSnapshotPort.class);
    private final AuthoritativeInventoryMovementPort movement = mock(AuthoritativeInventoryMovementPort.class);
    private final StoreService stores = mock(StoreService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ProcurementService service;

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 202L, 1L, "operator"));
        service = new ProcurementService(mapper, context, authorization, catalog, movement, stores,
            new UlidGenerator(clock), new ObjectMapper(), clock);
    }

    @Test
    void purchaseOrderRequiresSubmitAndDifferentApprover() {
        OrderHead draft = order("DRAFT", 0, 101L, null);
        when(mapper.lockOrder("TENANT_A", ORDER)).thenReturn(draft);
        when(mapper.updateOrderStatus(any())).thenReturn(1);
        when(mapper.findOrder("TENANT_A", ORDER)).thenReturn(order("SUBMITTED", 1, 101L, null));
        when(mapper.findOrderLines("TENANT_A", ORDER)).thenReturn(List.of());
        service.submitOrder(new SubmitOrder(ORDER, "trace-submit"));
        verify(movement, never()).applyOwnedMovement(any());

        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "creator"));
        when(mapper.lockOrder("TENANT_A", ORDER)).thenReturn(order("SUBMITTED", 1, 101L, null));
        assertThatThrownBy(() -> service.approveOrder(new ApproveOrder(ORDER, "trace-approve")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("职责分离");
    }

    @Test
    void receiptDraftHasNoStockEffectAndConfirmationUsesOwnerPort() {
        OrderHead approved = order("APPROVED", 2, 101L, 202L);
        OrderLine orderLine = orderLine(BigDecimal.ZERO);
        when(mapper.lockOrder("TENANT_A", ORDER)).thenReturn(approved);
        when(mapper.lockOrderLine("TENANT_A", ORDER, ORDER_LINE)).thenReturn(orderLine);
        when(catalog.requireUnit(701L, 302L)).thenReturn(new SkuUnitSnapshot(701L, "SKU", 302L,
            301L, 12, 1, false));
        when(mapper.findReceipt("TENANT_A", RECEIPT)).thenReturn(null, receipt("DRAFT", null, 0));
        when(mapper.findReceiptLines("TENANT_A", RECEIPT)).thenReturn(List.of(receiptLine()));

        service.createReceipt(new CreateReceipt(RECEIPT, ORDER,
            List.of(new com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLine(
                RECEIPT_LINE, ORDER_LINE, new BigDecimal("2"))), "trace-draft"));
        verify(movement, never()).applyOwnedMovement(any());
        verify(mapper, never()).updateOrderLineReceived(any());

        when(mapper.lockReceipt("TENANT_A", RECEIPT)).thenReturn(receipt("DRAFT", null, 0));
        when(mapper.countIncompleteOrderLines("TENANT_A", ORDER)).thenReturn(1);
        when(mapper.updateOrderStatus(any())).thenReturn(1);
        when(mapper.confirmReceipt(any())).thenReturn(1);
        when(movement.applyOwnedMovement(any())).thenReturn(new ApplyResult(EVENT, "PURCHASE_RECEIPT",
            RECEIPT, 1, false, false));
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        when(mapper.findReceipt("TENANT_A", RECEIPT)).thenReturn(receipt("CONFIRMED", EVENT, 1));

        service.confirmReceipt(new ConfirmReceipt(RECEIPT, EVENT, "trace-confirm"));

        ArgumentCaptor<OwnedMovement> captured = ArgumentCaptor.forClass(OwnedMovement.class);
        verify(movement).applyOwnedMovement(captured.capture());
        assertThat(captured.getValue().sourceType()).isEqualTo("PURCHASE_RECEIPT");
        assertThat(captured.getValue().lines().get(0).quantity()).isEqualByComparingTo("24");
        assertThat(captured.getValue().lines().get(0).movementType()).isEqualTo(MovementType.PURCHASE_RECEIPT_IN);
        verify(mapper).updateOrderLineReceived(any());
    }

    @Test
    void returnApprovalSeparatesRequesterAndAppendsOnlyOriginalReceiptQuantity() {
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(returnHead("PENDING_APPROVAL", null, 1, 101L, null));
        when(mapper.lockReceipt("TENANT_A", RECEIPT)).thenReturn(receipt("CONFIRMED", EVENT, 1));
        when(mapper.findReturnLines("TENANT_A", RETURN)).thenReturn(List.of(new
            com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnLine(
                RETURN_LINE, RETURN, RECEIPT_LINE, 701L, 301L, new BigDecimal("1"), new BigDecimal("12"))));
        when(mapper.lockReceiptLine("TENANT_A", RECEIPT, RECEIPT_LINE)).thenReturn(receiptLine());
        when(mapper.postReturn(any())).thenReturn(1);
        when(mapper.findReturn("TENANT_A", RETURN)).thenReturn(returnHead("POSTED", EVENT, 2, 101L, 202L));
        when(movement.applyOwnedMovement(any())).thenReturn(new ApplyResult(EVENT, "PURCHASE_RETURN",
            RETURN, 1, false, false));
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));

        service.approveReturn(new ApproveReturn(RETURN, EVENT, "trace-return"));

        ArgumentCaptor<OwnedMovement> captured = ArgumentCaptor.forClass(OwnedMovement.class);
        verify(movement).applyOwnedMovement(captured.capture());
        assertThat(captured.getValue().lines().get(0).movementType()).isEqualTo(MovementType.PURCHASE_RETURN_OUT);
        assertThat(captured.getValue().lines().get(0).quantity()).isEqualByComparingTo("12");

        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "requester"));
        when(mapper.lockReturn("TENANT_A", RETURN)).thenReturn(returnHead("PENDING_APPROVAL", null, 1, 101L, null));
        assertThatThrownBy(() -> service.approveReturn(new ApproveReturn(RETURN, EVENT, "trace-self")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("职责分离");
    }

    private OrderHead order(String status, long version, Long creator, Long approver) {
        return new OrderHead(ORDER, "01K2A000000000000000000120", 1101L, WAREHOUSE,
            LocalDate.of(2026, 8, 20), status, 0, creator, approver,
            approver == null ? null : LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), version);
    }

    private OrderLine orderLine(BigDecimal received) {
        return new OrderLine(ORDER_LINE, ORDER, 701L, 302L, 12, 1,
            new BigDecimal("10"), received, 100, 0);
    }

    private ReceiptHead receipt(String status, String event, long version) {
        return new ReceiptHead(RECEIPT, ORDER, event, 1101L, WAREHOUSE, status, "trace",
            "CONFIRMED".equals(status) ? LocalDateTime.ofInstant(NOW, ZoneOffset.UTC) : null, version);
    }

    private ReceiptLine receiptLine() {
        return new ReceiptLine(RECEIPT_LINE, RECEIPT, ORDER_LINE, 701L, 301L,
            new BigDecimal("2"), new BigDecimal("24"), BigDecimal.ZERO, 12, 1);
    }

    private ReturnHead returnHead(String status, String event, long version, Long requester, Long approver) {
        return new ReturnHead(RETURN, RECEIPT, event, status, "damaged", requester, approver,
            "POSTED".equals(status) ? LocalDateTime.ofInstant(NOW, ZoneOffset.UTC) : null, version);
    }
}
