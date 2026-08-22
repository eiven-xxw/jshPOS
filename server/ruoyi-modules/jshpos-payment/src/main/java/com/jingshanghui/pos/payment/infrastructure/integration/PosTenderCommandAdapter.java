package com.jingshanghui.pos.payment.infrastructure.integration;

import com.jingshanghui.pos.payment.application.model.PaymentCommands.CreateTenderPlan;
import com.jingshanghui.pos.payment.application.model.PaymentCommands.TenderAllocationInput;
import com.jingshanghui.pos.payment.application.service.TenderPlanService;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.port.PosTenderCommandPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 把可信 POS Outbox 冻结事件转换为 Payment Owner 命令；不接受支付成功观察。 */
@Component
@RequiredArgsConstructor
public class PosTenderCommandAdapter implements PosTenderCommandPort {

    private final TenderPlanService service;

    @Override
    public void apply(DeviceContext context, EventEnvelope event) {
        if (!"tender.plan-frozen.v1".equals(event.eventType())) {
            throw new ServiceException("TENDER-SYNC-001: 不支持的组合支付事件", 409);
        }
        Map<String, Object> payload = event.payload();
        String planId = text(payload, "planId");
        String storeId = text(payload, "storeId");
        String terminalId = text(payload, "terminalId");
        if (!planId.equals(event.aggregateId()) || !storeId.equals(context.storeId().toString())
            || !terminalId.equals(context.terminalId())) {
            throw new ServiceException("TENDER-SYNC-002: 设备、门店或计划身份不匹配", 409);
        }
        Object rawAllocations = payload.get("allocations");
        if (!(rawAllocations instanceof List<?> items)) {
            throw new ServiceException("TENDER-SYNC-003: 支付份额结构非法", 409);
        }
        List<TenderAllocationInput> allocations = new ArrayList<>();
        for (Object raw : items) {
            if (!(raw instanceof Map<?, ?> item)) {
                throw new ServiceException("TENDER-SYNC-003: 支付份额结构非法", 409);
            }
            allocations.add(new TenderAllocationInput(text(item, "allocationId"),
                integer(item, "sequenceNo"), text(item, "tenderType"), number(item, "amountMinor")));
        }
        service.create(new CreateTenderPlan(text(payload, "commandId"), text(payload, "idempotencyKey"),
            planId, text(payload, "orderId"), text(payload, "orderSnapshotSha256"), context.storeId(),
            context.terminalId(), number(payload, "receivableAmountMinor"), text(payload, "currency"),
            allocations, event.occurredAt()));
    }

    private String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ServiceException("TENDER-SYNC-004: " + key + " 缺失或格式非法", 409);
        }
        return text;
    }

    private long number(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new ServiceException("TENDER-SYNC-004: " + key + " 缺失或格式非法", 409);
        }
        return number.longValue();
    }

    private int integer(Map<?, ?> values, String key) {
        long value = number(values, key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new ServiceException("TENDER-SYNC-004: " + key + " 超出整数范围", 409);
        }
        return (int) value;
    }
}
