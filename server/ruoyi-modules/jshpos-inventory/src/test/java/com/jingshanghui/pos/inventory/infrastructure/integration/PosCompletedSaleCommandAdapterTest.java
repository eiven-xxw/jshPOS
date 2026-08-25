package com.jingshanghui.pos.inventory.infrastructure.integration;

import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryLineSnapshot;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.domain.SyncHash;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证普通成交只进入唯一销售仓，批次成交继续等待原批次冻结命令。 */
class PosCompletedSaleCommandAdapterTest {
    private static final String EVENT = id(1);
    private static final String ORDER = id(2);
    private static final String LINE = id(3);
    private static final String WAREHOUSE = id(4);
    private static final String DEVICE = id(5);
    private static final String TERMINAL = id(6);
    private static final String TRACE = id(7);
    private static final LocalDate DAY = LocalDate.of(2026, 8, 26);

    private final InventoryLedgerService inventory = mock(InventoryLedgerService.class);
    private final InventoryOrderSnapshotPort orders = mock(InventoryOrderSnapshotPort.class);
    private final AuthoritativeLotMovementPort lots = mock(AuthoritativeLotMovementPort.class);
    private final InventoryMapper mapper = mock(InventoryMapper.class);
    private final ObjectMapper json = new ObjectMapper();
    private final DeviceContext context = new DeviceContext("TENANT_A", DEVICE, 1101L, TERMINAL, 101L, "1.0");
    private PosCompletedSaleCommandAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new PosCompletedSaleCommandAdapter(inventory, orders, lots, mapper,
            Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC));
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 1101L,
            "COMPLETED", "PAID", DAY,
            List.of(new InventoryLineSnapshot(LINE, 701L, 301L, new BigDecimal("2.000000")))));
        when(mapper.findEffectiveWarehouseIdsByStore(any(), any(), any())).thenReturn(List.of(WAREHOUSE));
    }

    @Test
    void routesOrdinaryCompletedSaleToTheOnlyEffectiveWarehouse() {
        adapter.apply(context, event());

        ArgumentCaptor<ApplySale> command = ArgumentCaptor.forClass(ApplySale.class);
        verify(inventory).applySale(command.capture());
        assertThat(command.getValue()).isEqualTo(new ApplySale(EVENT, ORDER, WAREHOUSE, TRACE));
    }

    @Test
    void leavesLotTrackedOrderForTheFrozenLotEvent() {
        when(lots.requiresLotTracking(1101L, 701L, DAY)).thenReturn(true);

        adapter.apply(context, event());

        verify(inventory, never()).applySale(any());
        verify(mapper, never()).findEffectiveWarehouseIdsByStore(any(), any(), any());
    }

    @Test
    void failsClosedWhenSalesWarehouseIsMissingOrAmbiguous() {
        when(mapper.findEffectiveWarehouseIdsByStore(any(), any(), any()))
            .thenReturn(List.of(WAREHOUSE, id(8)));

        assertThatThrownBy(() -> adapter.apply(context, event()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-POS-004");
        verify(inventory, never()).applySale(any());
    }

    @Test
    void rejectsOrderOutsideTrustedStore() {
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 2202L,
            "COMPLETED", "PAID", DAY,
            List.of(new InventoryLineSnapshot(LINE, 701L, 301L, new BigDecimal("2.000000")))));

        assertThatThrownBy(() -> adapter.apply(context, event()))
            .isInstanceOf(ServiceException.class).hasMessageContaining("INV-POS-003");
        verify(inventory, never()).applySale(any());
    }

    private EventEnvelope event() {
        Map<String, Object> payload = Map.of("schemaVersion", "2.0", "orderId", ORDER,
            "businessDate", DAY.toString());
        return new EventEnvelope(EVENT, "order.command", "order.completed.v2", 2, ORDER, 4,
            DEVICE, "1101", TERMINAL, 4, Instant.parse("2026-08-26T08:00:00Z"), EVENT, TRACE,
            SyncHash.payload(json, payload), payload);
    }

    private static String id(int suffix) {
        return "01K2A0000000000000000000" + String.format("%02d", suffix);
    }
}
