package com.jingshanghui.pos.payment.infrastructure.integration;

import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateTenderPlan;
import com.jingshanghui.pos.payment.application.service.TenderPlanService;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** POS Outbox 到 Payment Owner 的可信适配回归；事件不能携带成功观察或自报租户。 */
class PosTenderCommandAdapterTest {

    private static final String PLAN = "01K2A000000000000000000091";
    private static final String TERMINAL = "01K2A000000000000000000095";

    @Test
    void mapsFrozenPlanUsingTrustedDeviceStoreAndTerminal() {
        TenderPlanService service = mock(TenderPlanService.class);
        PosTenderCommandAdapter adapter = new PosTenderCommandAdapter(service);
        DeviceContext context = new DeviceContext("TENANT_A", "DEVICE-A", 1101L, TERMINAL, 101L, "1.0");

        adapter.apply(context, event(Map.of(
            "planId", PLAN,
            "commandId", "01K2A000000000000000000096",
            "idempotencyKey", "tender-plan-a-1",
            "orderId", "01K2A000000000000000000093",
            "orderSnapshotSha256", "a".repeat(64),
            "storeId", "1101",
            "terminalId", TERMINAL,
            "receivableAmountMinor", 1299,
            "currency", "CNY",
            "allocations", List.of(
                Map.of("allocationId", "01K2A000000000000000000081", "sequenceNo", 1,
                    "tenderType", "ELECTRONIC", "amountMinor", 1000),
                Map.of("allocationId", "01K2A000000000000000000082", "sequenceNo", 2,
                    "tenderType", "CASH", "amountMinor", 299))
        )));

        ArgumentCaptor<CreateTenderPlan> command = ArgumentCaptor.forClass(CreateTenderPlan.class);
        verify(service).create(command.capture());
        assertThat(command.getValue().storeId()).isEqualTo(1101L);
        assertThat(command.getValue().terminalId()).isEqualTo(TERMINAL);
        assertThat(command.getValue().allocations()).hasSize(2);
    }

    @Test
    void rejectsClientStoreOrTerminalMismatchBeforeCallingOwner() {
        TenderPlanService service = mock(TenderPlanService.class);
        PosTenderCommandAdapter adapter = new PosTenderCommandAdapter(service);
        DeviceContext context = new DeviceContext("TENANT_A", "DEVICE-A", 1101L, TERMINAL, 101L, "1.0");
        assertThatThrownBy(() -> adapter.apply(context, event(Map.of(
            "planId", PLAN, "storeId", "2101", "terminalId", TERMINAL,
            "allocations", List.of())))).isInstanceOf(ServiceException.class)
            .hasMessageContaining("身份不匹配");
    }

    private EventEnvelope event(Map<String, Object> payload) {
        return new EventEnvelope("01K2A000000000000000000071", "business-facts",
            "tender.plan-frozen.v1", 1, PLAN, 1, "DEVICE-A", "1101", TERMINAL, 1,
            Instant.parse("2026-08-22T03:00:00Z"), "sync-idempotency-a-1",
            "01K2A000000000000000000072", "b".repeat(64), payload);
    }
}
