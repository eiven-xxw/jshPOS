package com.jingshanghui.pos.inventory.infrastructure.integration;

import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.port.PosCompletedSaleCommandPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 将普通 POS 成交事件路由到 Inventory Owner。
 *
 * <p>销售仓只能由可信门店已经发布的库存策略解析。若订单包含批次跟踪商品，则本端口
 * 不提前扣减，继续由随后到达的批次冻结事件在一个 Inventory 事务中完成基础库存、
 * 成本和 FEFO 批次效果，避免同一销售产生两次库存流水。</p>
 */
@Component
@RequiredArgsConstructor
public class PosCompletedSaleCommandAdapter implements PosCompletedSaleCommandPort {
    private static final String EVENT_TYPE = "order.completed.v2";

    private final InventoryLedgerService inventory;
    private final InventoryOrderSnapshotPort orders;
    private final AuthoritativeLotMovementPort lots;
    private final InventoryMapper mapper;
    private final Clock clock;

    @Override
    public void apply(DeviceContext context, EventEnvelope event) {
        if (!EVENT_TYPE.equals(event.eventType()) || event.eventVersion() != 2
            || !"2.0".equals(text(event.payload(), "schemaVersion"))) {
            throw conflict("INV-POS-001", "不支持的 POS 完成销售事件");
        }
        String orderId = text(event.payload(), "orderId");
        LocalDate businessDate = date(event.payload(), "businessDate");
        if (!orderId.equals(event.aggregateId()) || !context.deviceId().equals(event.deviceId())
            || !context.storeId().toString().equals(event.storeId())
            || !context.terminalId().equals(event.terminalId())) {
            throw forbidden("INV-POS-002", "订单或终端试图覆盖可信设备上下文");
        }
        InventoryRules.requireUlid(orderId, "orderId");
        InventoryOrderSnapshot order = orders.requireSnapshot(orderId);
        if (!context.storeId().equals(order.storeId()) || !businessDate.equals(order.businessDate())) {
            throw conflict("INV-POS-003", "成交事件与权威订单门店或业务日不一致");
        }

        boolean lotTracked = order.lines().stream().anyMatch(line ->
            lots.requiresLotTracking(order.storeId(), line.skuId(), order.businessDate()));
        if (lotTracked) {
            return;
        }
        LocalDateTime effectiveAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<String> warehouses = mapper.findEffectiveWarehouseIdsByStore(context.tenantId(), order.storeId(),
            effectiveAt);
        if (warehouses == null || warehouses.size() != 1) {
            throw conflict("INV-POS-004", "门店必须且只能解析出一个已生效销售仓");
        }
        inventory.applySale(new ApplySale(event.eventId(), orderId, warehouses.get(0), event.correlationId()));
    }

    private String text(Map<?, ?> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw conflict("INV-POS-005", field + " 缺失或格式非法");
        }
        return result;
    }

    private LocalDate date(Map<?, ?> payload, String field) {
        try {
            return LocalDate.parse(text(payload, field));
        } catch (DateTimeParseException exception) {
            throw conflict("INV-POS-005", field + " 必须为 ISO 日期");
        }
    }

    private ServiceException forbidden(String code, String message) {
        return new ServiceException(code + ": " + message, 403);
    }

    private ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
