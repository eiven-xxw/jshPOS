package com.jingshanghui.pos.inventory.infrastructure.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.AllocationView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ApplyResult;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.application.service.LotDataPackageService;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryLineSnapshot;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.domain.SyncHash;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 POS 冻结快照只作一致性证据，销售数量和 FEFO 仍由 Inventory Owner 决定。 */
class PosLotSaleCommandAdapterTest {
    private static final String EVENT = id(1);
    private static final String ORDER = id(2);
    private static final String LINE = id(3);
    private static final String WAREHOUSE = id(4);
    private static final String LOT = id(5);
    private static final String POLICY = id(6);
    private static final String DEVICE = id(7);
    private static final String TERMINAL = id(8);
    private static final String TRACE = id(9);
    private static final LocalDate DAY = LocalDate.of(2026, 8, 26);

    private final InventoryLedgerService inventory = mock(InventoryLedgerService.class);
    private final InventoryOrderSnapshotPort orders = mock(InventoryOrderSnapshotPort.class);
    private final AuthoritativeLotMovementPort lots = mock(AuthoritativeLotMovementPort.class);
    private final LotDataPackageService packages = mock(LotDataPackageService.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final DeviceContext context = new DeviceContext("TENANT_A", DEVICE, 1101L, TERMINAL, 101L, "1.0");
    private PosLotSaleCommandAdapter adapter;

    @BeforeEach
    void setUp() throws Exception {
        adapter = new PosLotSaleCommandAdapter(inventory, orders, lots, packages, json);
        when(orders.requireSnapshot(ORDER)).thenReturn(new InventoryOrderSnapshot(ORDER, 1101L,
            "COMPLETED", "PAID", DAY,
            List.of(new InventoryLineSnapshot(LINE, 701L, 301L, new BigDecimal("2.000000")))));
        when(lots.requiresLotTracking(1101L, 701L, DAY)).thenReturn(true);
        when(lots.allocateSale(any())).thenReturn(new ApplyResult(EVENT, "APPLIED", 1, "b".repeat(64),
            List.of(new AllocationView(id(10), ORDER, LINE, LOT, 701L, new BigDecimal("2.000000"),
                "SALE", POLICY, DAY.plusDays(10)))));
        byte[] packageBytes = json.writeValueAsBytes(Map.of("tenantId", "TENANT_A", "storeId", "1101",
            "warehouseId", WAREHOUSE, "packageVersion", 3));
        when(packages.latest(1101L, WAREHOUSE)).thenReturn(new PackageArtifact(packageBytes,
            InventoryHash.sha256(new String(packageBytes, StandardCharsets.UTF_8)), "TEST", new byte[64]));
    }

    @Test
    void appliesAuthoritativeInventoryAndAcceptsMatchingFrozenFefoSnapshot() throws Exception {
        adapter.apply(context, event(payload(LOT)));

        ArgumentCaptor<ApplySale> inventoryCommand = ArgumentCaptor.forClass(ApplySale.class);
        verify(inventory).applySale(inventoryCommand.capture());
        assertThat(inventoryCommand.getValue()).isEqualTo(new ApplySale(EVENT, ORDER, WAREHOUSE, TRACE));
        verify(lots).allocateSale(any());
    }

    @Test
    void rejectsClientSelectedLotWhenItDiffersFromServerFefo() throws Exception {
        assertThatThrownBy(() -> adapter.apply(context, event(payload(id(11)))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-SYNC-005");
        verify(inventory).applySale(any());
    }

    @Test
    void rejectsUntrustedStoreBeforeWritingInventory() throws Exception {
        Map<String, Object> payload = payload(LOT);
        payload.put("storeId", "2202");
        rehash(payload);

        assertThatThrownBy(() -> adapter.apply(context, event(payload)))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-SYNC-002");
        verify(inventory, never()).applySale(any());
    }

    private Map<String, Object> payload(String lotId) throws Exception {
        Map<String, Object> allocation = new LinkedHashMap<>();
        allocation.put("orderId", ORDER);
        allocation.put("orderLineId", LINE);
        allocation.put("skuId", 701);
        allocation.put("baseUnitId", 301);
        allocation.put("lotId", lotId);
        allocation.put("quantity", "2.000000");
        allocation.put("policyVersionId", POLICY);
        allocation.put("expiryDate", DAY.plusDays(10).toString());
        allocation.put("businessDate", DAY.toString());
        allocation.put("packageVersion", 3);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("orderId", ORDER);
        payload.put("storeId", "1101");
        payload.put("terminalId", TERMINAL);
        payload.put("warehouseId", WAREHOUSE);
        payload.put("businessDate", DAY.toString());
        payload.put("packageVersion", 3);
        payload.put("allocations", List.of(allocation));
        rehash(payload);
        return payload;
    }

    private void rehash(Map<String, Object> payload) throws Exception {
        payload.remove("payloadSha256");
        payload.put("payloadSha256", "sha256:" + InventoryHash.sha256(json.writeValueAsString(payload)));
    }

    private EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope(EVENT, "order.command", "inventory.lot-sale.requested.v1", 1,
            ORDER, 4, DEVICE, "1101", TERMINAL, 4, Instant.parse("2026-08-26T08:00:00Z"),
            EVENT, TRACE, SyncHash.payload(json, payload), payload);
    }

    private static String id(int suffix) {
        return "01K2A0000000000000000000" + String.format("%02d", suffix);
    }
}
