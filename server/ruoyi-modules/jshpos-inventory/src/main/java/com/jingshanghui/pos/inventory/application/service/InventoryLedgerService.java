package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplyReturn;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.ApplySale;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.PublishPolicy;
import com.jingshanghui.pos.inventory.application.model.InventoryCommands.RebuildBalance;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeCostPostingPort.PostedInventoryLedger;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.CommandView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerAggregate;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.LedgerView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.PolicyView;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.RebuildResult;
import com.jingshanghui.pos.inventory.domain.InventoryHash;
import com.jingshanghui.pos.inventory.domain.InventoryRules;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.inventory.domain.InventoryStates.NegativeStockMode;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.AnomalyWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceSeed;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.BalanceUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.CommandApplied;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.CommandWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.JournalWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.LedgerWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.PolicyWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.InventoryPersistenceParams.RebuildUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryLineSnapshot;
import com.jingshanghui.pos.order.application.port.InventoryOrderSnapshotPort.InventoryOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort;
import com.jingshanghui.pos.payment.application.port.InventoryRefundSnapshotPort.InventoryRefundSnapshot;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 不可变库存流水、余额投影和来源幂等的应用服务。
 *
 * <p>销售/退货事实来自 Owner 只读端口；本服务在单一数据库事务内写命令、流水、余额、审计和 Outbox。</p>
 */
@Service
@RequiredArgsConstructor
public class InventoryLedgerService implements AuthoritativeInventoryMovementPort {

    private final InventoryMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final InventoryOrderSnapshotPort orderSnapshotPort;
    private final InventoryRefundSnapshotPort refundSnapshotPort;
    private final AuthoritativeCostPostingPort costPostingPort;
    private final UlidGenerator ulids;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    /** 从权威已完成订单快照原子生成全部 SALE_OUT 流水。 */
    @Transactional
    public ApplyResult applySale(ApplySale command) {
        validateSourceCommand(command.eventId(), command.orderId(), command.warehouseId(), command.correlationId());
        InventoryOrderSnapshot order = orderSnapshotPort.requireSnapshot(command.orderId());
        InventoryRules.requireSourceState("ORDER", order.status(), order.paymentStatus());
        List<MovementLine> lines = order.lines().stream().map(line -> new MovementLine(line.orderLineId(),
            line.skuId(), line.unitId(), line.quantity(), MovementType.SALE_OUT)).toList();
        return apply(new SourceApply(command.eventId(), "ORDER", order.orderId(), command.warehouseId(),
            command.correlationId(), order.storeId(), order.businessDate(), MovementType.SALE_OUT,
            lines, hashOrder(command, order)));
    }

    /** 从权威成功原单退款快照生成 SALE_RETURN_IN，退货行必须与原订单行交叉一致。 */
    @Transactional
    public ApplyResult applyReturn(ApplyReturn command) {
        validateSourceCommand(command.eventId(), command.refundId(), command.warehouseId(), command.correlationId());
        InventoryRefundSnapshot refund = refundSnapshotPort.requireSnapshot(command.refundId());
        InventoryRules.requireSourceState("REFUND", refund.status(), null);
        InventoryOrderSnapshot order = orderSnapshotPort.requireSnapshot(refund.orderId());
        if (!order.storeId().equals(refund.storeId())) {
            throw new ServiceException("INV-REFUND-003: 退款与原订单门店不一致", 409);
        }
        Map<String, InventoryLineSnapshot> orderLines = new LinkedHashMap<>();
        order.lines().forEach(line -> orderLines.put(line.orderLineId(), line));
        List<InventoryLineSnapshot> returned = new ArrayList<>();
        refund.lines().forEach(line -> {
            InventoryLineSnapshot original = orderLines.get(line.orderLineId());
            if (original == null || line.quantity().compareTo(original.quantity()) > 0) {
                throw new ServiceException("INV-REFUND-004: 退款行不存在或数量超过原成交数量", 409);
            }
            returned.add(new InventoryLineSnapshot(original.orderLineId(), original.skuId(), original.unitId(),
                InventoryRules.positive(line.quantity(), "returnQuantity")));
        });
        List<MovementLine> lines = returned.stream().map(line -> new MovementLine(line.orderLineId(),
            line.skuId(), line.unitId(), line.quantity(), MovementType.SALE_RETURN_IN)).toList();
        return apply(new SourceApply(command.eventId(), "REFUND", refund.refundId(), command.warehouseId(),
            command.correlationId(), refund.storeId(), order.businessDate(), MovementType.SALE_RETURN_IN,
            lines, hashReturn(command, refund, returned)));
    }

    /**
     * 接收盘点或采购 Owner 已验证并持久化的数量事实。
     *
     * <p>该方法不是外部 API；同一调用事务内完成命令、流水、余额、审计和 Outbox。</p>
     */
    @Override
    @Transactional
    public ApplyResult applyOwnedMovement(OwnedMovement command) {
        if (command == null || command.sourceType() == null || command.sourceType().isBlank()
            || command.lines() == null) {
            throw new ServiceException("INV-OWNER-001: Owner 库存命令主体或行数非法", 409);
        }
        validateSourceCommand(command.eventId(), command.sourceId(), command.warehouseId(), command.correlationId());
        if (command.storeId() == null || command.storeId() <= 0 || command.businessDate() == null
            || command.lines().isEmpty() || command.lines().size() > 500) {
            throw new ServiceException("INV-OWNER-001: Owner 库存命令主体或行数非法", 409);
        }
        List<MovementLine> lines = command.lines().stream().map(line -> {
            InventoryRules.requireUlid(line.sourceLineId(), "sourceLineId");
            InventoryRules.requireOwnedMovement(command.sourceType(), line.movementType());
            return new MovementLine(line.sourceLineId(), line.skuId(), line.baseUnitId(),
                InventoryRules.positive(line.quantity(), "quantity"), line.movementType());
        }).toList();
        return apply(new SourceApply(command.eventId(), command.sourceType(), command.sourceId(),
            command.warehouseId(), command.correlationId(), command.storeId(), command.businessDate(),
            null, lines, hashOwned(command, lines)));
    }

    /** 发布后策略不可修改；便利店默认应首先发布 DENY 版本。 */
    @Transactional
    public PolicyView publishPolicy(PublishPolicy command) {
        InventoryRules.requireUlid(command.policyVersionId(), "policyVersionId");
        InventoryRules.requireUlid(command.warehouseId(), "warehouseId");
        requireCorrelation(command.correlationId());
        if (command.storeId() == null || command.storeId() <= 0 || command.effectiveFrom() == null) {
            throw new ServiceException("INV-POLICY-001: 门店或生效时间非法", 409);
        }
        NegativeStockMode mode = parseMode(command.negativeStockMode());
        if (mode == NegativeStockMode.ALLOW_WITH_PERMISSION) {
            throw new ServiceException("INV-POLICY-002: ALLOW_WITH_PERMISSION 尚未准入", 409);
        }
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        authorizationService.requireStoreAccess(command.storeId());
        LocalDateTime now = now();
        LocalDateTime effective = LocalDateTime.ofInstant(command.effectiveFrom(), ZoneOffset.UTC);
        mapper.insertPolicy(new PolicyWrite(command.policyVersionId(), principal.tenantId(), command.storeId(),
            command.warehouseId(), mode.name(), effective, principal.userId(), now));
        String hash = InventoryHash.sha256(InventoryHash.canonical(List.of(command.policyVersionId(),
            command.storeId(), command.warehouseId(), mode, command.effectiveFrom())));
        mapper.insertAudit(new JournalWrite(ulids.next(), principal.tenantId(), command.storeId(),
            "INVENTORY_POLICY_PUBLISHED", "STOCK_POLICY", command.policyVersionId(), principal.userId(),
            command.policyVersionId(), command.correlationId(), null, mode.name(), hash, "VERSION_PUBLISHED", now));
        writeOutbox(principal.tenantId(), "inventory.policy.published.v1", command.policyVersionId(), 1,
            command.correlationId(), Map.of("policyVersionId", command.policyVersionId(),
                "storeId", command.storeId(), "warehouseId", command.warehouseId(),
                "negativeStockMode", mode.name(), "effectiveFrom", command.effectiveFrom().toString()), now);
        return new PolicyView(command.policyVersionId(), command.storeId(), command.warehouseId(), mode.name(), effective);
    }

    @Transactional(readOnly = true)
    public BalanceView findBalance(String warehouseId, Long skuId) {
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        requireSku(skuId);
        String tenantId = tenantContext.requireTenantId();
        PolicyView policy = requireWarehousePolicy(tenantId, warehouseId);
        authorizationService.requireStoreAccess(policy.storeId());
        BalanceView balance = mapper.findBalance(tenantId, warehouseId, skuId);
        if (balance == null) throw new ServiceException("INV-BALANCE-001: 库存维度不存在或不可见", 404);
        return balance;
    }

    @Transactional(readOnly = true)
    public List<LedgerView> findLedger(String warehouseId, Long skuId) {
        BalanceView balance = findBalance(warehouseId, skuId);
        return mapper.findLedger(tenantContext.requireTenantId(), balance.dimensionKey());
    }

    /** 管理员受控重建余额投影；只聚合流水，不修改任何库存事实。 */
    @Transactional
    public RebuildResult rebuild(RebuildBalance command) {
        InventoryRules.requireUlid(command.warehouseId(), "warehouseId");
        requireSku(command.skuId());
        requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        PolicyView policy = requireWarehousePolicy(principal.tenantId(), command.warehouseId());
        authorizationService.requireTenantAdministrator();
        authorizationService.requireStoreAccess(policy.storeId());
        String dimension = InventoryHash.dimension(principal.tenantId(), command.warehouseId(), command.skuId());
        BalanceView balance = mapper.lockBalance(principal.tenantId(), dimension);
        if (balance == null) throw new ServiceException("INV-BALANCE-001: 库存维度不存在或不可见", 404);
        LedgerAggregate aggregate = mapper.aggregateLedger(principal.tenantId(), dimension);
        BigDecimal ledgerQuantity = aggregate.ledgerQuantity().setScale(InventoryRules.QUANTITY_SCALE);
        boolean changed = balance.onHandQuantity().compareTo(ledgerQuantity) != 0
            || balance.lastLedgerSequence() != aggregate.lastLedgerSequence();
        LocalDateTime at = now();
        if (changed && mapper.rebuildBalance(new RebuildUpdate(principal.tenantId(), dimension, ledgerQuantity,
            aggregate.lastLedgerSequence(), balance.recordVersion(), at)) != 1) {
            throw new ServiceException("INV-BALANCE-002: 重建期间余额版本冲突", 409);
        }
        String hash = InventoryHash.sha256(InventoryHash.canonical(List.of(dimension,
            balance.onHandQuantity(), ledgerQuantity, aggregate.ledgerCount())));
        mapper.insertAudit(new JournalWrite(ulids.next(), principal.tenantId(), policy.storeId(),
            changed ? "INVENTORY_BALANCE_REBUILT" : "INVENTORY_BALANCE_VERIFIED", "STOCK_BALANCE", dimension,
            principal.userId(), ulids.next(), command.correlationId(), balance.onHandQuantity().toPlainString(),
            ledgerQuantity.toPlainString(), hash, "LEDGER_RECOMPUTE", at));
        return new RebuildResult(dimension, balance.onHandQuantity(), ledgerQuantity,
            aggregate.ledgerCount(), changed);
    }

    private ApplyResult apply(SourceApply source) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(source.storeId());
        CommandView existing = mapper.findCommand(principal.tenantId(), source.eventId());
        if (existing != null) {
            if (!existing.requestSha256().equals(source.requestHash())) {
                throw new ServiceException("INV-IDEM-001: 相同 eventId 对应不同权威快照", 409);
            }
            if (!"APPLIED".equals(existing.status())) {
                throw new ServiceException("INV-IDEM-002: 原库存命令状态未收敛", 409);
            }
            return new ApplyResult(existing.eventId(), existing.sourceType(), existing.sourceId(),
                existing.affectedLines(), existing.negativeAlert(), true);
        }
        PolicyView policy = mapper.findEffectivePolicy(principal.tenantId(), source.storeId(), source.warehouseId(), now());
        if (policy == null) throw new ServiceException("INV-POLICY-003: 门店仓缺少已生效库存策略", 409);
        NegativeStockMode mode = parseMode(policy.negativeStockMode());
        LocalDateTime at = now();
        mapper.insertCommand(new CommandWrite(source.eventId(), principal.tenantId(), source.requestHash(),
            source.sourceType(), source.sourceId(), source.warehouseId(), source.storeId(), source.correlationId(),
            principal.userId(), at));
        boolean negative = false;
        List<MovementLine> lines = source.lines().stream()
            .sorted(Comparator.comparing(MovementLine::skuId).thenComparing(MovementLine::sourceLineId))
            .toList();
        if (lines.isEmpty() || lines.size() > 500) {
            throw new ServiceException("INV-CMD-001: 库存命令行数必须为1至500", 409);
        }
        for (MovementLine line : lines) {
            negative |= applyLine(new LineApply(principal, source, policy, mode, line, at));
        }
        if (mapper.completeCommand(new CommandApplied(principal.tenantId(), source.eventId(), lines.size(),
            negative, at)) != 1) {
            throw new ServiceException("INV-CMD-002: 库存命令完成状态冲突", 409);
        }
        mapper.insertAudit(new JournalWrite(ulids.next(), principal.tenantId(), source.storeId(),
            "INVENTORY_SOURCE_APPLIED", "STOCK_COMMAND", source.eventId(), principal.userId(), source.eventId(),
            source.correlationId(), "PROCESSING", "APPLIED", source.requestHash(), source.sourceType(), at));
        return new ApplyResult(source.eventId(), source.sourceType(), source.sourceId(), lines.size(), negative, false);
    }

    private boolean applyLine(LineApply input) {
        MovementLine line = input.line();
        requireSku(line.skuId());
        if (line.baseUnitId() == null || line.baseUnitId() <= 0) {
            throw new ServiceException("INV-LINE-001: 基础单位非法", 409);
        }
        BigDecimal delta = InventoryRules.signedDelta(line.movementType(), line.quantity());
        String tenantId = input.principal().tenantId();
        String dimension = InventoryHash.dimension(tenantId, input.source().warehouseId(), line.skuId());
        mapper.insertBalanceIfAbsent(new BalanceSeed(tenantId, dimension, input.source().warehouseId(), line.skuId()));
        BalanceView before = mapper.lockBalance(tenantId, dimension);
        if (before == null) throw new ServiceException("INV-BALANCE-003: 无法锁定库存维度", 409);
        BigDecimal afterQuantity = before.onHandQuantity().add(delta).setScale(InventoryRules.QUANTITY_SCALE);
        BigDecimal availableAfter = InventoryRules.available(afterQuantity, before.reservedQuantity(),
            before.frozenQuantity(), before.safetyStockQuantity());
        boolean negative = InventoryRules.requiresNegativeAlert(input.mode(), availableAfter);
        long sequence = before.lastLedgerSequence() + 1;
        String inventoryLedgerId = ulids.next();
        mapper.insertLedger(new LedgerWrite(inventoryLedgerId, tenantId, dimension, sequence,
            input.source().warehouseId(), line.skuId(), line.baseUnitId(), "SALEABLE",
            line.movementType().name(), before.onHandQuantity(), delta, afterQuantity,
            input.source().sourceType(), input.source().sourceId(), line.sourceLineId(), input.source().eventId(),
            input.policy().policyVersionId(), input.source().businessDate(), input.principal().userId(),
            input.source().correlationId(), input.at()));
        if (mapper.updateBalance(new BalanceUpdate(tenantId, dimension, afterQuantity, sequence,
            before.recordVersion(), input.at())) != 1) {
            throw new ServiceException("INV-BALANCE-004: 库存余额并发冲突", 409);
        }
        // 成本 Owner 必须在当前事务内消费刚写入的库存事实；失败时库存流水和余额一并回滚。
        costPostingPort.applyPostedLedger(new PostedInventoryLedger(inventoryLedgerId, sequence, dimension,
            input.source().warehouseId(), input.source().storeId(), line.skuId(), line.baseUnitId(),
            line.movementType().name(), before.onHandQuantity(), delta, afterQuantity,
            input.source().sourceType(), input.source().sourceId(), line.sourceLineId(),
            input.source().eventId(), null, input.source().businessDate(), input.source().correlationId(), input.at()));
        if (negative) {
            mapper.insertAnomaly(new AnomalyWrite(ulids.next(), tenantId, input.source().storeId(),
                input.source().warehouseId(), line.skuId(), "NEGATIVE_STOCK", availableAfter,
                input.policy().policyVersionId(), input.source().eventId(), input.at()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", "1.0");
        payload.put("warehouseId", input.source().warehouseId());
        payload.put("skuId", line.skuId());
        payload.put("movementType", line.movementType().name());
        payload.put("quantityDelta", delta.toPlainString());
        payload.put("quantityAfter", afterQuantity.toPlainString());
        payload.put("policyVersionId", input.policy().policyVersionId());
        payload.put("correlationId", input.source().correlationId());
        writeOutbox(tenantId, "inventory.stock.changed.v1", dimension, sequence,
            input.source().correlationId(), payload, input.at());
        if (negative) {
            writeOutbox(tenantId, "inventory.negative.detected.v1", dimension, sequence,
                input.source().correlationId(), payload, input.at());
        }
        mapper.insertAudit(new JournalWrite(ulids.next(), tenantId, input.source().storeId(),
            "INVENTORY_" + line.movementType().name(), "STOCK_BALANCE", dimension,
            input.principal().userId(), input.source().eventId(), input.source().correlationId(),
            before.onHandQuantity().toPlainString(), afterQuantity.toPlainString(), input.source().requestHash(),
            negative ? "NEGATIVE_ALLOWED_ALERT" : "SOURCE_EFFECT", input.at()));
        return negative;
    }

    private void writeOutbox(String tenantId, String eventType, String aggregateId, long version,
                             String correlationId, Map<String, Object> payload, LocalDateTime at) {
        String eventId = ulids.next();
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("eventId", eventId);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("INV-EVENT-001: 库存事件序列化失败", 500);
        }
        mapper.insertOutbox(new OutboxWrite(eventId, tenantId, eventType, aggregateId, version, correlationId,
            json, InventoryHash.sha256(json), at));
    }

    private PolicyView requireWarehousePolicy(String tenantId, String warehouseId) {
        PolicyView policy = mapper.findEffectivePolicyByWarehouse(tenantId, warehouseId, now());
        if (policy == null) throw new ServiceException("INV-POLICY-003: 门店仓缺少已生效库存策略", 409);
        return policy;
    }

    private String hashOrder(ApplySale command, InventoryOrderSnapshot order) {
        List<Object> values = new ArrayList<>(List.of(command.eventId(), order.orderId(), command.warehouseId(),
            order.storeId(), order.status(), order.paymentStatus(), order.businessDate()));
        order.lines().stream().sorted(Comparator.comparing(InventoryLineSnapshot::orderLineId)).forEach(line -> {
            values.add(line.orderLineId()); values.add(line.skuId()); values.add(line.unitId());
            values.add(InventoryRules.positive(line.quantity(), "saleQuantity").toPlainString());
        });
        return InventoryHash.sha256(InventoryHash.canonical(values));
    }

    private String hashReturn(ApplyReturn command, InventoryRefundSnapshot refund,
                              List<InventoryLineSnapshot> returned) {
        List<Object> values = new ArrayList<>(List.of(command.eventId(), refund.refundId(), refund.orderId(),
            command.warehouseId(), refund.storeId(), refund.status()));
        returned.stream().sorted(Comparator.comparing(InventoryLineSnapshot::orderLineId)).forEach(line -> {
            values.add(line.orderLineId()); values.add(line.skuId()); values.add(line.unitId());
            values.add(line.quantity().toPlainString());
        });
        return InventoryHash.sha256(InventoryHash.canonical(values));
    }

    private String hashOwned(OwnedMovement command, List<MovementLine> lines) {
        List<Object> values = new ArrayList<>(List.of(command.eventId(), command.sourceType(), command.sourceId(),
            command.warehouseId(), command.storeId(), command.businessDate()));
        lines.stream().sorted(Comparator.comparing(MovementLine::sourceLineId)).forEach(line -> {
            values.add(line.sourceLineId()); values.add(line.skuId()); values.add(line.baseUnitId());
            values.add(line.quantity().toPlainString()); values.add(line.movementType().name());
        });
        return InventoryHash.sha256(InventoryHash.canonical(values));
    }

    private void validateSourceCommand(String eventId, String sourceId, String warehouseId, String correlationId) {
        InventoryRules.requireUlid(eventId, "eventId");
        InventoryRules.requireUlid(sourceId, "sourceId");
        InventoryRules.requireUlid(warehouseId, "warehouseId");
        requireCorrelation(correlationId);
    }

    private void requireCorrelation(String value) {
        if (value == null || value.isBlank() || value.length() > 96) {
            throw new ServiceException("INV-INPUT-001: correlationId 格式非法", 409);
        }
    }

    private void requireSku(Long skuId) {
        if (skuId == null || skuId <= 0) throw new ServiceException("INV-INPUT-002: skuId 非法", 409);
    }

    private NegativeStockMode parseMode(String value) {
        try {
            return NegativeStockMode.valueOf(value);
        } catch (RuntimeException exception) {
            throw new ServiceException("INV-POLICY-004: 负库存策略枚举非法", 409);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
    }

    /** 一次来源命令的不可变内部参数对象。 */
    private record SourceApply(String eventId, String sourceType, String sourceId, String warehouseId,
                               String correlationId, Long storeId, LocalDate businessDate,
                               MovementType movementType, List<MovementLine> lines,
                               String requestHash) {
        private SourceApply {
            lines = List.copyOf(lines);
        }
    }

    /** 单行事务计算上下文，避免同类型参数错位。 */
    private record LineApply(TrustedPrincipal principal, SourceApply source, PolicyView policy,
                             NegativeStockMode mode, MovementLine line, LocalDateTime at) {
    }

    /** 已规范化的内部库存移动行，数量始终为正，方向由 movementType 决定。 */
    private record MovementLine(String sourceLineId, Long skuId, Long baseUnitId,
                                BigDecimal quantity, MovementType movementType) {
    }
}
