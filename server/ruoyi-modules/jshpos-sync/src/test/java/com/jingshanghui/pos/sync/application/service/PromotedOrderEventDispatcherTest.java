package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;
import com.jingshanghui.pos.order.application.service.PromotedCashOrderService;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证同步载荷只能映射业务内容，租户、门店、终端和操作人始终来自可信设备上下文。 */
class PromotedOrderEventDispatcherTest {

    private final PromotedCashOrderService orders = mock(PromotedCashOrderService.class);
    private final PromotionSnapshotIngestionPort snapshots = mock(PromotionSnapshotIngestionPort.class);
    private final PromotedOrderEventDispatcher dispatcher = new PromotedOrderEventDispatcher(orders, snapshots);
    private final DeviceContext trusted = new DeviceContext("TENANT_A", "DEVICE_A", 1101L,
        "01K2A000000000000000000011", 101L, "1.1");

    @Test
    void mapsFullFrozenPayloadWithoutAcceptingClientTenant() {
        EventEnvelope event = event(payload("1101", trusted.terminalId(), "101"));

        dispatcher.apply(trusted, event);

        ArgumentCaptor<SubmitPromotedCashOrder> captor = ArgumentCaptor.forClass(SubmitPromotedCashOrder.class);
        ArgumentCaptor<PromotionSnapshotIngestionPort.SnapshotCommand> snapshotCaptor =
            ArgumentCaptor.forClass(PromotionSnapshotIngestionPort.SnapshotCommand.class);
        verify(orders).submit(captor.capture());
        verify(snapshots).ingest(snapshotCaptor.capture());
        SubmitPromotedCashOrder command = captor.getValue();
        assertThat(command.storeId()).isEqualTo(1101L);
        assertThat(command.terminalId()).isEqualTo(trusted.terminalId());
        assertThat(command.cashierId()).isEqualTo("101");
        assertThat(command.discountAmountMinor()).isEqualTo(200L);
        assertThat(command.receivableAmountMinor()).isEqualTo(1100L);
        assertThat(snapshotCaptor.getValue().quoteFingerprint()).isEqualTo("2".repeat(64));
        assertThat(snapshotCaptor.getValue().settlementFingerprint()).isEqualTo("3".repeat(64));
        assertThat(command.lines()).singleElement().satisfies(line -> {
            assertThat(line.quantity()).isEqualTo("1.000000");
            assertThat(line.sourceAllocations()).containsEntry("RULE_A", 200L);
            assertThat(line.measuredBarcodeSnapshot()).isNotNull();
            assertThat(line.measuredBarcodeSnapshot().rawBarcode()).isEqualTo("2300123012994");
            assertThat(line.measuredBarcodeSnapshot().templateId()).isEqualTo("501");
            assertThat(line.measuredBarcodeSnapshot().amountMinor()).isEqualTo(1299L);
        });
    }

    @Test
    void rejectsContextReplacementBeforeCallingOrderOwner() {
        Map<String, Object> payload = payload("2101", trusted.terminalId(), "101");
        payload.put("tenantId", "TENANT_B");

        assertThatThrownBy(() -> dispatcher.apply(trusted, event(payload)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("SYNC_CONTEXT_MISMATCH");
        verifyNoInteractions(orders, snapshots);
    }

    private EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope("01K2A000000000000000000201", "orders", "order.submitted.v2", 2,
            "01K2A000000000000000000031", 1, "DEVICE_A", "1101", trusted.terminalId(), 1,
            Instant.parse("2026-08-16T02:00:00Z"), "settlement-key-0001",
            "01K2A000000000000000000211", "sha256:" + "a".repeat(64), payload);
    }

    private Map<String, Object> payload(String storeId, String terminalId, String cashierId) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("lineId", "01K2A000000000000000000041");
        line.put("lineNo", 1);
        line.put("skuId", "701");
        line.put("skuCode", "A-SKU");
        line.put("barcode", "6900000000012");
        line.put("productName", "合成商品A");
        line.put("unitId", "301");
        line.put("unitCode", "PCS");
        line.put("quantity", "1.000000");
        line.put("unitPriceMinor", 1299);
        line.put("grossAmountMinor", 1299);
        line.put("discountAmountMinor", 200);
        line.put("surchargeAmountMinor", 1);
        line.put("payableAmountMinor", 1100);
        line.put("priceSource", "PROMOTION_SNAPSHOT");
        line.put("sourceAllocations", Map.of("RULE_A", 200));
        line.put("measuredBarcodeSnapshot", measurement());
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tenantId", "TENANT_A");
        value.put("orderId", "01K2A000000000000000000031");
        value.put("localOrderNo", "A-T1-1");
        value.put("storeId", storeId);
        value.put("terminalId", terminalId);
        value.put("cashierId", cashierId);
        value.put("shiftId", "01K2A000000000000000000021");
        value.put("businessDate", "2026-08-16");
        value.put("storeTimezone", "Asia/Shanghai");
        value.put("catalogVersion", 1);
        value.put("priceVersion", 1);
        value.put("industryTemplateVersion", "CONVENIENCE.1");
        value.put("promotionSnapshotId", "01K2A000000000000000000051");
        value.put("quoteId", "01K2A000000000000000000052");
        value.put("promotionEngineVersion", "promotion-engine-1.0.0");
        value.put("promotionSnapshotHash", "sha256:" + "1".repeat(64));
        value.put("quoteFingerprint", "2".repeat(64));
        value.put("settlementFingerprint", "3".repeat(64));
        value.put("packageVersion", 1);
        value.put("orderSnapshotHash", "sha256:" + "4".repeat(64));
        value.put("manualEventRefs", List.of("01K2A000000000000000000061"));
        value.put("grossAmountMinor", 1299);
        value.put("discountAmountMinor", 200);
        value.put("surchargeAmountMinor", 1);
        value.put("receivableAmountMinor", 1100);
        value.put("tenderedAmountMinor", 1200);
        value.put("lines", List.of(line));
        return value;
    }

    private Map<String, Object> measurement() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("rawBarcode", "2300123012994");
        value.put("skuCode", "00123");
        value.put("encodedValue", "01299");
        value.put("quantity", "1.000000");
        value.put("amountMinor", 1299);
        value.put("unitPriceMinor", 1299);
        value.put("currency", "CNY");
        value.put("templateId", "501");
        value.put("templateVersion", 1);
        value.put("templateSha256", "a".repeat(64));
        value.put("parseSha256", "b".repeat(64));
        value.put("roundingApplied", false);
        value.put("occurredAt", "2026-08-16T02:00:00Z");
        return value;
    }
}
