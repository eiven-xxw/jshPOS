package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.model.OrderCommands.CloseShift;
import com.jingshanghui.pos.order.application.model.OrderCommands.OpenSyncedShift;
import com.jingshanghui.pos.order.application.port.ShiftSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;

/**
 * 将 POS 班次 Outbox 事件交给 Order Owner 的正式应用服务。
 * 本组件只做版本化载荷转换，可信租户、门店、终端和员工均来自服务端设备上下文。
 */
@Component
@RequiredArgsConstructor
public class ShiftEventDispatcher {
    private final ShiftSubmissionPort shifts;

    public void apply(DeviceContext context, EventEnvelope event) {
        if ("shift.opened.v1".equals(event.eventType())) {
            open(context, event);
        } else if ("shift.closed.v1".equals(event.eventType())) {
            close(event);
        }
    }

    private void open(DeviceContext context, EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        if (!event.aggregateId().equals(text(payload, "shiftId"))
            || !context.storeId().toString().equals(text(payload, "storeId"))
            || !context.terminalId().equals(text(payload, "terminalId"))
            || !context.userId().toString().equals(text(payload, "cashierId"))) {
            throw new ServiceException("SYNC_CONTEXT_MISMATCH: 班次载荷试图覆盖可信设备上下文", 403);
        }
        shifts.openSynced(new OpenSyncedShift(event.correlationId(), event.idempotencyKey(), event.aggregateId(),
            context.storeId(), context.terminalId(), context.userId().toString(),
            LocalDate.parse(text(payload, "businessDate")), text(payload, "storeTimezone"),
            number(payload, "openingCashMinor"), 1, event.occurredAt()));
    }

    private void close(EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        if (!event.aggregateId().equals(text(payload, "shiftId")) || event.aggregateVersion() < 2) {
            throw new ServiceException("SYNC_PAYLOAD_INVALID: 关班身份或版本无效", 400);
        }
        Object approval = payload.get("approvalId");
        shifts.close(new CloseShift(event.correlationId(), event.idempotencyKey(), event.aggregateId(),
            number(payload, "actualCashMinor"), event.aggregateVersion() - 1,
            approval == null ? null : String.valueOf(approval), event.occurredAt()));
    }

    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw new ServiceException("SYNC_PAYLOAD_INVALID: " + field + " 不能为空", 400);
        }
        return result;
    }

    private long number(Map<String, Object> payload, String field) {
        try {
            return new java.math.BigDecimal(String.valueOf(payload.get(field))).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ServiceException("SYNC_PAYLOAD_INVALID: " + field + " 必须为整数", 400);
        }
    }
}
