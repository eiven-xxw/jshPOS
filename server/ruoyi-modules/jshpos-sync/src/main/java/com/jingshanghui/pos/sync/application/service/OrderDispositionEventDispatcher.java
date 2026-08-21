package com.jingshanghui.pos.sync.application.service;

import com.jingshanghui.pos.order.application.port.OrderDispositionSubmissionPort;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/** 将取消墓碑和成交后处置路由交给Order Owner；客户端租户与门店声明不具授权效力。 */
@Component
@RequiredArgsConstructor
public class OrderDispositionEventDispatcher {
    private final OrderDispositionSubmissionPort dispositions;

    public void apply(DeviceContext context, EventEnvelope event) {
        Map<String, Object> payload = event.payload();
        requireTrustedScope(context, event, payload);
        String type = text(payload, "dispositionType");
        if ("order.cancelled.v1".equals(event.eventType()) && !"CANCEL_BEFORE_COMPLETION".equals(type)) {
            throw invalid("取消事件的处置类型不匹配");
        }
        if ("order.reversal-routed.v1".equals(event.eventType()) && "CANCEL_BEFORE_COMPLETION".equals(type)) {
            throw invalid("反向处置事件不得伪装为取消");
        }
        dispositions.record(event.eventId(), event.aggregateId(), text(payload, "orderId"),
            context.storeId(), context.terminalId(), text(payload, "shiftId"), context.userId(),
            date(payload, "businessDate"), type, text(payload, "fromStatus"),
            text(payload, "effectiveStatus"), text(payload, "reasonCode"), text(payload, "reasonText"),
            optionalText(payload, "authorizationRef"), text(payload, "orderSnapshotSha256"),
            text(payload, "requestSha256"), number(payload, "aggregateVersion"),
            LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
    }

    private void requireTrustedScope(DeviceContext context, EventEnvelope event, Map<String, Object> payload) {
        if (!event.aggregateId().equals(text(payload, "dispositionId"))
            || !context.storeId().toString().equals(text(payload, "storeId"))
            || !context.terminalId().equals(text(payload, "terminalId"))
            || !context.userId().toString().equals(text(payload, "cashierId"))) {
            throw new ServiceException("ORDER_DISPOSITION_CONTEXT_MISMATCH: 客户端试图覆盖可信设备上下文", 403);
        }
    }

    private String text(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw invalid(field + "不能为空");
        return text;
    }

    private String optionalText(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw invalid(field + "无效");
        return text;
    }

    private long number(Map<String, Object> payload, String field) {
        try {
            return new BigDecimal(String.valueOf(payload.get(field))).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid(field + "必须为整数");
        }
    }

    private LocalDate date(Map<String, Object> payload, String field) {
        try {
            return LocalDate.parse(text(payload, field));
        } catch (java.time.DateTimeException exception) {
            throw invalid(field + "必须为规范日期");
        }
    }

    private ServiceException invalid(String message) {
        return new ServiceException("ORDER_DISPOSITION_INVALID: " + message, 400);
    }
}
