package com.jingshanghui.pos.inventory.infrastructure.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PackageArtifact;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.AllocationView;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.SaleCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.SaleLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.inventory.application.service.InventoryLedgerService;
import com.jingshanghui.pos.inventory.application.service.LotDataPackageService;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.domain.LotInventoryRules;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryLineSnapshot;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.sync.application.model.SyncModels.DeviceContext;
import com.jingshanghui.pos.sync.application.model.SyncModels.EventEnvelope;
import com.jingshanghui.pos.sync.application.port.PosLotSaleCommandPort;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 将可信 POS 批次成交快照映射为 Inventory Owner 销售命令。
 *
 * <p>客户端冻结的批次仅作为离线一致性证据。库存 Owner 先读取已成交订单并写入不可变
 * SALE_OUT，再按服务端锁内 FEFO 分配批次；两侧快照不一致时整个 Sync 事务失败关闭。</p>
 */
@Component
@RequiredArgsConstructor
public class PosLotSaleCommandAdapter implements PosLotSaleCommandPort {
    private static final String EVENT_TYPE = "inventory.lot-sale.requested.v1";
    private static final int MAX_ALLOCATIONS = 500;

    private final InventoryLedgerService inventory;
    private final InventoryOrderSnapshotPort orders;
    private final AuthoritativeLotMovementPort lots;
    private final LotDataPackageService packages;
    private final ObjectMapper objectMapper;

    @Override
    public void apply(DeviceContext context, EventEnvelope event) {
        if (!EVENT_TYPE.equals(event.eventType()) || event.eventVersion() != 1
            || !"1.0".equals(text(event.payload(), "schemaVersion"))) {
            throw conflict("LOT-SYNC-001", "不支持的 POS 批次销售事件");
        }
        Map<String, Object> payload = event.payload();
        String orderId = text(payload, "orderId");
        String warehouseId = text(payload, "warehouseId");
        LocalDate businessDate = date(payload, "businessDate");
        long packageVersion = positiveLong(payload, "packageVersion");
        if (!orderId.equals(event.aggregateId())
            || !context.storeId().toString().equals(text(payload, "storeId"))
            || !context.terminalId().equals(text(payload, "terminalId"))) {
            throw forbidden("LOT-SYNC-002", "订单、门店或终端试图覆盖可信设备上下文");
        }
        InventoryRules.requireUlid(orderId, "orderId");
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        verifySnapshotHash(payload);
        verifyCurrentPackage(context, warehouseId, packageVersion);

        InventoryOrderSnapshot order = orders.requireSnapshot(orderId);
        if (!context.storeId().equals(order.storeId()) || !businessDate.equals(order.businessDate())) {
            throw conflict("LOT-SYNC-003", "批次快照与权威订单门店或业务日不一致");
        }
        Map<String, InventoryLineSnapshot> orderLines = order.lines().stream().collect(Collectors.toMap(
            InventoryLineSnapshot::orderLineId, Function.identity(), (left, right) -> {
                throw conflict("LOT-SYNC-004", "权威订单行身份重复");
            }, LinkedHashMap::new));
        List<FrozenAllocation> claimed = claimedAllocations(payload, orderId, businessDate,
            packageVersion, orderLines);
        List<SaleLine> tracked = order.lines().stream()
            .filter(line -> lots.requiresLotTracking(order.storeId(), line.skuId(), order.businessDate()))
            .map(line -> new SaleLine(line.orderLineId(), line.skuId(), line.unitId(), line.quantity()))
            .toList();

        // applySale 只从 Order Owner 读取数量，并在同一事务内写基础库存、成本和服务端 FEFO 批次事实。
        inventory.applySale(new ApplySale(event.eventId(), orderId, warehouseId, event.correlationId()));
        List<FrozenAllocation> authoritative = tracked.isEmpty() ? List.of()
            : authoritativeAllocations(lots.allocateSale(new SaleCommand(new CommandSource(event.eventId(),
                "ORDER", orderId, warehouseId, order.storeId(), order.businessDate(), event.correlationId()),
                tracked)), orderLines);
        if (!sort(claimed).equals(sort(authoritative))) {
            throw conflict("LOT-SYNC-005", "POS 冻结批次与服务端权威 FEFO 分配不一致");
        }
    }

    private void verifySnapshotHash(Map<String, Object> payload) {
        String supplied = prefixedHash(payload, "payloadSha256");
        Map<String, Object> frozen = new LinkedHashMap<>(payload);
        frozen.remove("payloadSha256");
        try {
            String actual = InventoryHash.sha256(objectMapper.writeValueAsString(frozen));
            if (!actual.equals(supplied)) {
                throw conflict("LOT-SYNC-006", "POS 批次快照摘要不一致");
            }
        } catch (JsonProcessingException exception) {
            throw conflict("LOT-SYNC-006", "POS 批次快照无法规范化");
        }
    }

    private void verifyCurrentPackage(DeviceContext context, String warehouseId, long packageVersion) {
        PackageArtifact artifact = packages.latest(context.storeId(), warehouseId);
        try {
            JsonNode root = objectMapper.readTree(artifact.payload());
            boolean valid = root.path("packageVersion").canConvertToLong()
                && root.path("packageVersion").longValue() == packageVersion
                && context.tenantId().equals(root.path("tenantId").asText())
                && context.storeId().toString().equals(root.path("storeId").asText())
                && warehouseId.equals(root.path("warehouseId").asText());
            if (!valid) {
                throw conflict("LOT-SYNC-007", "POS 批次包版本不是当前可信门店仓发布版本");
            }
        } catch (IOException exception) {
            throw conflict("LOT-SYNC-007", "当前批次包不可解析");
        }
    }

    private List<FrozenAllocation> claimedAllocations(Map<String, Object> payload, String orderId,
                                                       LocalDate businessDate, long packageVersion,
                                                       Map<String, InventoryLineSnapshot> orderLines) {
        List<?> values = array(payload, "allocations");
        if (values.size() > MAX_ALLOCATIONS) {
            throw invalid("LOT-SYNC-008", "批次分配超过单事件上限");
        }
        List<FrozenAllocation> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Object raw : values) {
            if (!(raw instanceof Map<?, ?> source)) {
                throw invalid("LOT-SYNC-008", "批次分配必须为对象");
            }
            Map<String, Object> value = stringMap(source);
            String lineId = text(value, "orderLineId");
            String lotId = text(value, "lotId");
            String policyVersionId = text(value, "policyVersionId");
            InventoryRules.requireUlid(lineId, "orderLineId");
            InventoryRules.requireUlid(lotId, "lotId");
            InventoryRules.requireUlid(policyVersionId, "policyVersionId");
            InventoryLineSnapshot line = orderLines.get(lineId);
            BigDecimal quantity = quantity(value, "quantity");
            if (line == null || !orderId.equals(text(value, "orderId"))
                || !line.skuId().equals(positiveLong(value, "skuId"))
                || !line.unitId().equals(positiveLong(value, "baseUnitId"))
                || !businessDate.equals(date(value, "businessDate"))
                || packageVersion != positiveLong(value, "packageVersion")) {
                throw conflict("LOT-SYNC-009", "批次分配与权威订单行或冻结上下文不一致");
            }
            if (!identities.add(lineId + '|' + lotId)) {
                throw conflict("LOT-SYNC-010", "同一订单行与批次出现重复分配");
            }
            result.add(new FrozenAllocation(lineId, line.skuId(), line.unitId(), lotId, quantity,
                policyVersionId, date(value, "expiryDate")));
        }
        return result;
    }

    private List<FrozenAllocation> authoritativeAllocations(
        com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ApplyResult result,
        Map<String, InventoryLineSnapshot> orderLines) {
        List<FrozenAllocation> values = new ArrayList<>();
        for (AllocationView allocation : result.allocations()) {
            InventoryLineSnapshot line = orderLines.get(allocation.sourceLineId());
            if (line == null || !line.skuId().equals(allocation.skuId())) {
                throw conflict("LOT-SYNC-011", "Inventory Owner 返回了订单外批次分配");
            }
            values.add(new FrozenAllocation(allocation.sourceLineId(), allocation.skuId(), line.unitId(),
                allocation.lotId(), allocation.quantity().setScale(LotInventoryRules.QUANTITY_SCALE),
                allocation.policyVersionId(), allocation.expiryDate()));
        }
        return values;
    }

    private List<FrozenAllocation> sort(List<FrozenAllocation> values) {
        return values.stream().sorted(Comparator.comparing(FrozenAllocation::orderLineId)
            .thenComparing(FrozenAllocation::expiryDate).thenComparing(FrozenAllocation::lotId))
            .toList();
    }

    private BigDecimal quantity(Map<String, Object> source, String field) {
        Object value = source.get(field);
        try {
            return LotInventoryRules.exactQuantity(new BigDecimal(String.valueOf(value)), field);
        } catch (NumberFormatException exception) {
            throw invalid("LOT-SYNC-012", field + " 必须为精确正数");
        }
    }

    private LocalDate date(Map<String, Object> source, String field) {
        try {
            return LocalDate.parse(text(source, field));
        } catch (DateTimeParseException exception) {
            throw invalid("LOT-SYNC-012", field + " 必须为 ISO 日期");
        }
    }

    private long positiveLong(Map<?, ?> source, String field) {
        Object value = source.get(field);
        try {
            long parsed = new BigDecimal(String.valueOf(value)).longValueExact();
            if (parsed <= 0) throw new ArithmeticException("non-positive");
            return parsed;
        } catch (ArithmeticException | NumberFormatException exception) {
            throw invalid("LOT-SYNC-012", field + " 必须为正整数");
        }
    }

    private String text(Map<?, ?> source, String field) {
        Object value = source.get(field);
        if (!(value instanceof String result) || result.isBlank()) {
            throw invalid("LOT-SYNC-012", field + " 缺失或格式非法");
        }
        return result;
    }

    private String prefixedHash(Map<String, Object> source, String field) {
        String value = text(source, field);
        if (!value.matches("^sha256:[a-f0-9]{64}$")) {
            throw invalid("LOT-SYNC-012", field + " 必须为 sha256 摘要");
        }
        return value.substring(7);
    }

    private List<?> array(Map<String, Object> source, String field) {
        Object value = source.get(field);
        if (!(value instanceof List<?> result)) {
            throw invalid("LOT-SYNC-012", field + " 必须为数组");
        }
        return result;
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, 400);
    }

    private ServiceException forbidden(String code, String message) {
        return new ServiceException(code + ": " + message, 403);
    }

    private ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }

    /** POS 与服务端都可比较的最小冻结批次事实，不包含服务端生成的 allocation_id。 */
    private record FrozenAllocation(String orderLineId, Long skuId, Long baseUnitId, String lotId,
                                    BigDecimal quantity, String policyVersionId, LocalDate expiryDate) { }
}
