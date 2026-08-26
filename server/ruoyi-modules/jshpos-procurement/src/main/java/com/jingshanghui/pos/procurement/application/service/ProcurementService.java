package com.jingshanghui.pos.procurement.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovementLine;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ChangeSupplierState;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CloseOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ConfirmReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateSupplier;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.SubmitOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.SubmitReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderDetail;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptDetail;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptLine;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.Supplier;
import com.jingshanghui.pos.procurement.domain.ProcurementHash;
import com.jingshanghui.pos.procurement.domain.ProcurementRules;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentProcurementSnapshotPort;
import com.jingshanghui.pos.procurement.application.port.ReplenishmentPurchaseDraftPort;
import com.jingshanghui.pos.procurement.application.port.BusinessMigrationSupplierPort;
import com.jingshanghui.pos.procurement.infrastructure.persistence.ProcurementPersistenceParams.*;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ProcurementMapper;
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
 * 供应商、采购单、确认收货和原收货退货应用服务。
 *
 * <p>数量事实先在采购 Owner 持久化，再在同一事务内调用库存 Owner 追加不可变流水。</p>
 */
@Service
@RequiredArgsConstructor
public class ProcurementService implements ReplenishmentProcurementSnapshotPort, ReplenishmentPurchaseDraftPort,
    BusinessMigrationSupplierPort {

    private final ProcurementMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final InventoryCatalogSnapshotPort catalogPort;
    private final AuthoritativeInventoryMovementPort movementPort;
    private final ProcurementLotCoordinator lotCoordinator;
    private final StoreService storeService;
    private final UlidGenerator ulids;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    /** 纯命令规则不持有 Mapper 或事务，公开事务入口仍由本服务独占。 */
    private final ProcurementCommandPolicy commandPolicy = new ProcurementCommandPolicy();

    @Transactional
    public Supplier createSupplier(CreateSupplier command) {
        ProcurementRules.ulid(command.supplierId(), "supplierId");
        String code = ProcurementRules.text(command.code(), 64, "PUR-SUP-002");
        String name = ProcurementRules.text(command.name(), 160, "PUR-SUP-003");
        commandPolicy.requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        Supplier existing = mapper.findSupplier(principal.tenantId(), command.supplierId());
        if (existing != null) {
            if (!existing.code().equals(code) || !existing.name().equals(name)) {
                throw new ServiceException("PUR-IDEM-001: 相同 supplierId 对应不同内容", 409);
            }
            return existing;
        }
        LocalDateTime at = now();
        mapper.insertSupplier(new SupplierWrite(command.supplierId(), principal.tenantId(), code, name,
            principal.userId(), at));
        audit(principal, null, "SUPPLIER_CREATED", "SUPPLIER", command.supplierId(), command.supplierId(),
            command.correlationId(), null, "ACTIVE", "CREATED", at);
        return mapper.findSupplier(principal.tenantId(), command.supplierId());
    }

    /** 开业迁移只复用正式供应商创建规则，不向 Migration Owner 暴露供应商 Mapper。 */
    @Override
    @Transactional
    public SupplierMigrationResult importSupplier(SupplierMigrationCommand command) {
        if (command == null || command.rowSha256() == null
            || !command.rowSha256().matches("^[a-f0-9]{64}$")) {
            throw new ServiceException("DMT-SUPPLIER-INPUT: 供应商迁移行摘要非法", 400);
        }
        Supplier before = mapper.findSupplier(tenantContext.requireTenantId(), command.supplierId());
        Supplier supplier = createSupplier(new CreateSupplier(command.supplierId(), command.code(), command.name(),
            command.correlationId()));
        return new SupplierMigrationResult(supplier.supplierId(), supplier.code(), supplier.status(),
            command.rowSha256(), before != null);
    }

    @Transactional
    public Supplier changeSupplierState(ChangeSupplierState command) {
        ProcurementRules.ulid(command.supplierId(), "supplierId");
        commandPolicy.requireCorrelation(command.correlationId());
        String next = ProcurementRules.supplierState(command.state());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireTenantAdministrator();
        Supplier supplier = mapper.lockSupplier(principal.tenantId(), command.supplierId());
        if (supplier == null) throw new ServiceException("PUR-SUP-004: 供应商不存在或不可见", 404);
        if (supplier.status().equals(next)) return supplier;
        String reason = ProcurementRules.text(command.reason(), 256, "PUR-SUP-005");
        if (mapper.updateSupplierState(new SupplierStateUpdate(principal.tenantId(), command.supplierId(),
            supplier.status(), next, supplier.version(), now())) != 1) {
            throw new ServiceException("PUR-SUP-006: 供应商版本冲突", 409);
        }
        audit(principal, null, "SUPPLIER_STATE_CHANGED", "SUPPLIER", command.supplierId(), command.supplierId(),
            command.correlationId(), supplier.status(), next, reason, now());
        return mapper.findSupplier(principal.tenantId(), command.supplierId());
    }

    /** 创建不改变库存的采购单，并冻结商品单位换算、商业价格和税率快照。 */
    @Transactional
    public OrderDetail createOrder(CreateOrder command) {
        commandPolicy.validateOrder(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.storeId());
        int tolerance = ProcurementRules.tolerance(command.overReceiptToleranceBps());
        if (tolerance > 0) authorizationService.requireTenantAdministrator();
        String requestHash = commandPolicy.hashOrder(command);
        OrderHead existing = mapper.findOrder(principal.tenantId(), command.orderId());
        if (existing != null) {
            if (!requestHash.equals(mapper.findOrderRequestHash(principal.tenantId(), command.orderId()))) {
                throw new ServiceException("PUR-IDEM-002: 相同 orderId 对应不同采购单", 409);
            }
            return orderDetail(command.orderId());
        }
        Supplier supplier = mapper.lockSupplier(principal.tenantId(), command.supplierId());
        if (supplier == null || !"ACTIVE".equals(supplier.status())) {
            throw new ServiceException("PUR-SUP-007: 供应商不存在、不可见或已停用", 409);
        }
        LocalDateTime at = now();
        mapper.insertOrder(new OrderWrite(command.orderId(), principal.tenantId(), command.supplierId(),
            command.storeId(), command.warehouseId(), command.expectedDate(), tolerance, "MANUAL", null, requestHash,
            command.correlationId(), principal.userId(), at));
        for (com.jingshanghui.pos.procurement.application.model.ProcurementCommands.OrderLine line
            : command.lines().stream().sorted(Comparator.comparing(
                com.jingshanghui.pos.procurement.application.model.ProcurementCommands.OrderLine::orderLineId)).toList()) {
            ProcurementRules.ulid(line.orderLineId(), "orderLineId");
            SkuUnitSnapshot unit = catalogPort.requireUnit(line.skuId(), line.unitId());
            BigDecimal quantity = ProcurementRules.quantity(line.orderedQuantity(), "orderedQuantity");
            ProcurementRules.toBaseQuantity(quantity, unit.numerator(), unit.denominator());
            mapper.insertOrderLine(new OrderLineWrite(line.orderLineId(), principal.tenantId(), command.orderId(),
                line.skuId(), line.unitId(), unit.numerator(), unit.denominator(), quantity,
                ProcurementRules.money(line.unitPriceMinor()), ProcurementRules.taxRate(line.taxRateBps()), at));
        }
        audit(principal, command.storeId(), "PURCHASE_ORDER_CREATED", "PURCHASE_ORDER", command.orderId(),
            command.orderId(), command.correlationId(), null, "DRAFT", "CREATED", at);
        return orderDetail(command.orderId());
    }

    /** 提交采购单审批，不产生库存效果。 */
    @Transactional
    public OrderDetail submitOrder(SubmitOrder command) {
        ProcurementRules.ulid(command.orderId(), "orderId");
        commandPolicy.requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        OrderHead order = requireLockedOrder(principal.tenantId(), command.orderId());
        authorizationService.requireStoreAccess(order.storeId());
        ProcurementRules.requireDraft(order.status());
        LocalDateTime at = now();
        updateOrderState(order, "SUBMITTED", null, null, at);
        audit(principal, order.storeId(), "PURCHASE_ORDER_SUBMITTED", "PURCHASE_ORDER", command.orderId(),
            command.orderId(), command.correlationId(), "DRAFT", "SUBMITTED", "SUBMITTED", at);
        return orderDetail(command.orderId());
    }

    /** 审批冻结采购承诺，不产生库存效果。 */
    @Transactional
    public OrderDetail approveOrder(ApproveOrder command) {
        ProcurementRules.ulid(command.orderId(), "orderId");
        commandPolicy.requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        OrderHead order = requireLockedOrder(principal.tenantId(), command.orderId());
        authorizationService.requireStoreAccess(order.storeId());
        ProcurementRules.requireSubmitted(order.status());
        if (principal.userId().equals(order.creatorUserId())) {
            throw new ServiceException("PUR-STATE-005: 采购单创建与审批必须职责分离", 409);
        }
        LocalDateTime at = now();
        updateOrderState(order, "APPROVED", principal.userId(), at, at);
        audit(principal, order.storeId(), "PURCHASE_ORDER_APPROVED", "PURCHASE_ORDER", command.orderId(),
            command.orderId(), command.correlationId(), "SUBMITTED", "APPROVED", "APPROVED", at);
        event(principal.tenantId(), "procurement.purchase-order.approved.v1", command.orderId(),
            order.version() + 1, command.correlationId(), Map.of("orderId", command.orderId(),
                "storeId", order.storeId(), "warehouseId", order.warehouseId()), at);
        return orderDetail(command.orderId());
    }

    /** 显式关闭采购单，不产生库存效果。 */
    @Transactional
    public OrderDetail closeOrder(CloseOrder command) {
        ProcurementRules.ulid(command.orderId(), "orderId");
        commandPolicy.requireCorrelation(command.correlationId());
        String reason = ProcurementRules.text(command.reason(), 256, "PUR-ORDER-004");
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        OrderHead order = requireLockedOrder(principal.tenantId(), command.orderId());
        authorizationService.requireStoreAccess(order.storeId());
        ProcurementRules.requireClosable(order.status());
        LocalDateTime at = now();
        updateOrderState(order, "CLOSED", null, null, at);
        audit(principal, order.storeId(), "PURCHASE_ORDER_CLOSED", "PURCHASE_ORDER", command.orderId(),
            command.orderId(), command.correlationId(), order.status(), "CLOSED", reason, at);
        return orderDetail(command.orderId());
    }

    /** 创建收货草稿，草稿不改变采购累计收货和库存。 */
    @Transactional
    public ReceiptDetail createReceipt(CreateReceipt command) {
        commandPolicy.validateReceiptDraft(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReceiptHead existing = mapper.findReceipt(principal.tenantId(), command.receiptId());
        if (existing != null) {
            if (!existing.orderId().equals(command.orderId())) {
                throw new ServiceException("PUR-IDEM-003: 相同 receiptId 对应不同收货", 409);
            }
            return receiptDetail(command.receiptId());
        }
        OrderHead order = requireLockedOrder(principal.tenantId(), command.orderId());
        authorizationService.requireStoreAccess(order.storeId());
        ProcurementRules.requireReceivable(order.status());
        LocalDateTime at = now();
        mapper.insertReceipt(new ReceiptWrite(command.receiptId(), principal.tenantId(), command.orderId(),
            order.storeId(), order.warehouseId(), command.correlationId(), principal.userId(), at));
        for (com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLine input : command.lines()) {
            ProcurementRules.ulid(input.receiptLineId(), "receiptLineId");
            ProcurementRules.ulid(input.orderLineId(), "orderLineId");
            OrderLine line = mapper.lockOrderLine(principal.tenantId(), command.orderId(), input.orderLineId());
            if (line == null) throw new ServiceException("PUR-RECEIPT-003: 采购单行不存在或不可见", 404);
            BigDecimal quantity = ProcurementRules.quantity(input.receivedQuantity(), "receivedQuantity");
            ProcurementRules.requireWithinReceiptLimit(line.orderedQuantity(), line.receivedQuantity(),
                quantity, order.overReceiptToleranceBps());
            SkuUnitSnapshot unit = catalogPort.requireUnit(line.skuId(), line.purchaseUnitId());
            if (unit.numerator() != line.conversionNumerator() || unit.denominator() != line.conversionDenominator()) {
                throw new ServiceException("PUR-UNIT-003: 商品单位已变化，必须重建采购单版本", 409);
            }
            BigDecimal base = ProcurementRules.toBaseQuantity(quantity, line.conversionNumerator(),
                line.conversionDenominator());
            mapper.insertReceiptLine(new ReceiptLineWrite(input.receiptLineId(), principal.tenantId(),
                command.receiptId(), line.orderLineId(), line.skuId(), unit.baseUnitId(), quantity, base,
                line.conversionNumerator(), line.conversionDenominator(), at));
        }
        audit(principal, order.storeId(), "PURCHASE_RECEIPT_CREATED", "PURCHASE_RECEIPT",
            command.receiptId(), command.receiptId(), command.correlationId(), null, "DRAFT", "CREATED", at);
        return receiptDetail(command.receiptId());
    }

    /** 确认既有收货草稿与 PURCHASE_RECEIPT_IN 流水处于同一事务。 */
    @Transactional
    public ReceiptDetail confirmReceipt(ConfirmReceipt command) {
        commandPolicy.validateReceiptConfirmation(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReceiptHead receipt = mapper.lockReceipt(principal.tenantId(), command.receiptId());
        if (receipt == null) throw new ServiceException("PUR-RECEIPT-005: 收货单不存在或不可见", 404);
        authorizationService.requireStoreAccess(receipt.storeId());
        if ("CONFIRMED".equals(receipt.status())) {
            if (!command.eventId().equals(receipt.sourceEventId())) {
                throw new ServiceException("PUR-IDEM-003: 相同 receiptId 对应不同确认事件", 409);
            }
            OrderHead order = requireLockedOrder(principal.tenantId(), receipt.orderId());
            LocalDate businessDate = storeService.businessDate(order.storeId(), clock.instant()).businessDate();
            lotCoordinator.applyReceiptLots(command, receipt, order,
                mapper.findReceiptLines(principal.tenantId(), command.receiptId()), businessDate);
            return receiptDetail(command.receiptId());
        }
        if (!"DRAFT".equals(receipt.status())) throw new ServiceException("PUR-RECEIPT-007: 收货单不可确认", 409);
        OrderHead order = requireLockedOrder(principal.tenantId(), receipt.orderId());
        ProcurementRules.requireReceivable(order.status());
        LocalDateTime at = now();
        List<OwnedMovementLine> movements = new ArrayList<>();
        List<ReceiptLine> confirmedLines = mapper.findReceiptLines(principal.tenantId(), command.receiptId());
        for (ReceiptLine receiptLine : confirmedLines) {
            OrderLine line = mapper.lockOrderLine(principal.tenantId(), order.orderId(), receiptLine.orderLineId());
            if (line == null) throw new ServiceException("PUR-RECEIPT-003: 采购单行不存在或不可见", 404);
            ProcurementRules.requireWithinReceiptLimit(line.orderedQuantity(), line.receivedQuantity(),
                receiptLine.receivedQuantity(), order.overReceiptToleranceBps());
            mapper.updateOrderLineReceived(new OrderLineReceivedUpdate(principal.tenantId(), line.orderLineId(),
                receiptLine.receivedQuantity(), at));
            movements.add(new OwnedMovementLine(receiptLine.receiptLineId(), receiptLine.skuId(),
                receiptLine.baseUnitId(), receiptLine.baseQuantity(), MovementType.PURCHASE_RECEIPT_IN));
        }
        LocalDate businessDate = storeService.businessDate(order.storeId(), clock.instant()).businessDate();
        String next = mapper.countIncompleteOrderLines(principal.tenantId(), order.orderId()) == 0
            ? "RECEIVED" : "PARTIALLY_RECEIVED";
        updateOrderState(order, next, null, null, at);
        if (mapper.confirmReceipt(new ReceiptConfirm(principal.tenantId(), command.receiptId(),
            command.eventId(), receipt.version(), at)) != 1) {
            throw new ServiceException("PUR-RECEIPT-004: 收货确认版本冲突", 409);
        }
        // 先让已确认采购事实在当前事务内可见，再由库存 Owner 写数量与成本；任一步失败会整体回滚。
        movementPort.applyOwnedMovement(new OwnedMovement(command.eventId(), "PURCHASE_RECEIPT",
            command.receiptId(), order.warehouseId(), order.storeId(), businessDate,
            command.correlationId(), movements));
        lotCoordinator.applyReceiptLots(command, receipt, order, confirmedLines, businessDate);
        audit(principal, order.storeId(), "PURCHASE_RECEIPT_CONFIRMED", "PURCHASE_RECEIPT",
            command.receiptId(), command.eventId(), command.correlationId(), "DRAFT", "CONFIRMED",
            "INVENTORY_LEDGER_APPENDED", at);
        event(principal.tenantId(), "procurement.receipt.confirmed.v1", command.receiptId(), 1,
            command.correlationId(), Map.of("receiptId", command.receiptId(), "orderId", order.orderId(),
                "sourceEventId", command.eventId(), "lineCount", movements.size()), at);
        return receiptDetail(command.receiptId());
    }

    /** 创建原收货退货草稿，不改变累计退货和库存。 */
    @Transactional
    public ReturnHead createReturn(CreateReturn command) {
        commandPolicy.validateReturnDraft(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnHead existing = mapper.findReturn(principal.tenantId(), command.purchaseReturnId());
        if (existing != null) {
            if (!existing.receiptId().equals(command.receiptId())) {
                throw new ServiceException("PUR-IDEM-004: 相同 purchaseReturnId 对应不同退货", 409);
            }
            return existing;
        }
        ReceiptHead receipt = mapper.lockReceipt(principal.tenantId(), command.receiptId());
        if (receipt == null || !"CONFIRMED".equals(receipt.status())) {
            throw new ServiceException("PUR-RETURN-002: 原收货不存在或未确认", 409);
        }
        authorizationService.requireStoreAccess(receipt.storeId());
        LocalDateTime at = now();
        String reason = ProcurementRules.text(command.reason(), 256, "PUR-RETURN-003");
        mapper.insertReturn(new ReturnWrite(command.purchaseReturnId(), principal.tenantId(), command.receiptId(),
            reason, command.correlationId(), principal.userId(), at));
        for (com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReturnLine input : command.lines()) {
            ProcurementRules.ulid(input.returnLineId(), "returnLineId");
            ProcurementRules.ulid(input.receiptLineId(), "receiptLineId");
            ReceiptLine line = mapper.lockReceiptLine(principal.tenantId(), command.receiptId(), input.receiptLineId());
            if (line == null) throw new ServiceException("PUR-RETURN-004: 原收货行不存在或不可见", 404);
            BigDecimal quantity = ProcurementRules.quantity(input.returnQuantity(), "returnQuantity");
            ProcurementRules.requireWithinReturnLimit(line.receivedQuantity(), line.returnedQuantity(), quantity);
            BigDecimal base = ProcurementRules.toBaseQuantity(quantity, line.conversionNumerator(),
                line.conversionDenominator());
            mapper.insertReturnLine(new ReturnLineWrite(input.returnLineId(), principal.tenantId(),
                command.purchaseReturnId(), line.receiptLineId(), line.skuId(), line.baseUnitId(), quantity, base, at));
        }
        audit(principal, receipt.storeId(), "PURCHASE_RETURN_CREATED", "PURCHASE_RETURN",
            command.purchaseReturnId(), command.purchaseReturnId(), command.correlationId(), null, "DRAFT", reason, at);
        return mapper.findReturn(principal.tenantId(), command.purchaseReturnId());
    }

    /** 提交采购退货审批，不产生库存效果。 */
    @Transactional
    public ReturnHead submitReturn(SubmitReturn command) {
        ProcurementRules.ulid(command.purchaseReturnId(), "purchaseReturnId");
        commandPolicy.requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnHead value = mapper.lockReturn(principal.tenantId(), command.purchaseReturnId());
        if (value == null) throw new ServiceException("PUR-RETURN-007: 采购退货不存在或不可见", 404);
        ReceiptHead receipt = mapper.findReceipt(principal.tenantId(), value.receiptId());
        if (receipt == null) throw new ServiceException("PUR-RETURN-002: 原收货不存在或不可见", 404);
        authorizationService.requireStoreAccess(receipt.storeId());
        if (!"DRAFT".equals(value.status())) throw new ServiceException("PUR-RETURN-008: 采购退货不是草稿", 409);
        LocalDateTime at = now();
        updateReturnState(value, "PENDING_APPROVAL", at);
        audit(principal, receipt.storeId(), "PURCHASE_RETURN_SUBMITTED", "PURCHASE_RETURN",
            command.purchaseReturnId(), command.purchaseReturnId(), command.correlationId(),
            "DRAFT", "PENDING_APPROVAL", "SUBMITTED", at);
        return mapper.findReturn(principal.tenantId(), command.purchaseReturnId());
    }

    /** 审批原收货退货与 PURCHASE_RETURN_OUT 流水处于同一事务。 */
    @Transactional
    public ReturnHead approveReturn(ApproveReturn command) {
        commandPolicy.validateReturnApproval(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        ReturnHead value = mapper.lockReturn(principal.tenantId(), command.purchaseReturnId());
        if (value == null) throw new ServiceException("PUR-RETURN-007: 采购退货不存在或不可见", 404);
        if ("POSTED".equals(value.status())) {
            if (!command.eventId().equals(value.sourceEventId())) {
                throw new ServiceException("PUR-IDEM-004: 相同 purchaseReturnId 对应不同审批事件", 409);
            }
            ReceiptHead receipt = mapper.lockReceipt(principal.tenantId(), value.receiptId());
            if (receipt == null) throw new ServiceException("PUR-RETURN-002: 原收货不存在或不可见", 404);
            authorizationService.requireStoreAccess(receipt.storeId());
            List<OwnedMovementLine> replayMovements = mapper.findReturnLines(principal.tenantId(),
                    command.purchaseReturnId()).stream()
                .map(input -> new OwnedMovementLine(input.returnLineId(), input.skuId(), input.baseUnitId(),
                    input.baseQuantity(), MovementType.PURCHASE_RETURN_OUT)).toList();
            LocalDate businessDate = storeService.businessDate(receipt.storeId(), clock.instant()).businessDate();
            lotCoordinator.applyReturnLots(command, receipt, replayMovements, businessDate);
            return value;
        }
        if (!"PENDING_APPROVAL".equals(value.status())) {
            throw new ServiceException("PUR-RETURN-009: 采购退货尚未提交审批", 409);
        }
        if (principal.userId().equals(value.requesterUserId())) {
            throw new ServiceException("PUR-RETURN-010: 采购退货申请与审批必须职责分离", 409);
        }
        ReceiptHead receipt = mapper.lockReceipt(principal.tenantId(), value.receiptId());
        if (receipt == null || !"CONFIRMED".equals(receipt.status())) {
            throw new ServiceException("PUR-RETURN-002: 原收货不存在或未确认", 409);
        }
        authorizationService.requireStoreAccess(receipt.storeId());
        LocalDateTime at = now();
        List<OwnedMovementLine> movements = new ArrayList<>();
        for (com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReturnLine input
            : mapper.findReturnLines(principal.tenantId(), command.purchaseReturnId())) {
            ReceiptLine line = mapper.lockReceiptLine(principal.tenantId(), receipt.receiptId(), input.receiptLineId());
            if (line == null) throw new ServiceException("PUR-RETURN-004: 原收货行不存在或不可见", 404);
            ProcurementRules.requireWithinReturnLimit(line.receivedQuantity(), line.returnedQuantity(),
                input.returnQuantity());
            mapper.updateReceiptLineReturned(new ReceiptLineReturnedUpdate(principal.tenantId(),
                line.receiptLineId(), input.returnQuantity(), at));
            movements.add(new OwnedMovementLine(input.returnLineId(), input.skuId(), input.baseUnitId(),
                input.baseQuantity(), MovementType.PURCHASE_RETURN_OUT));
        }
        LocalDate businessDate = storeService.businessDate(receipt.storeId(), clock.instant()).businessDate();
        if (mapper.postReturn(new ReturnPost(principal.tenantId(), command.purchaseReturnId(), command.eventId(),
            principal.userId(), value.version(), at)) != 1) {
            throw new ServiceException("PUR-RETURN-005: 采购退货版本冲突", 409);
        }
        // 退货先在当前事务内成为已入账权威事实，成本端口才能读取原收货关系；失败仍整体回滚。
        movementPort.applyOwnedMovement(new OwnedMovement(command.eventId(), "PURCHASE_RETURN",
            command.purchaseReturnId(), receipt.warehouseId(), receipt.storeId(), businessDate,
            command.correlationId(), movements));
        lotCoordinator.applyReturnLots(command, receipt, movements, businessDate);
        audit(principal, receipt.storeId(), "PURCHASE_RETURN_POSTED", "PURCHASE_RETURN",
            command.purchaseReturnId(), command.eventId(), command.correlationId(), "PENDING_APPROVAL", "POSTED",
            "INVENTORY_LEDGER_APPENDED", at);
        event(principal.tenantId(), "procurement.return.posted.v1", command.purchaseReturnId(), value.version() + 1,
            command.correlationId(), Map.of("purchaseReturnId", command.purchaseReturnId(),
                "receiptId", receipt.receiptId(), "sourceEventId", command.eventId(),
                "lineCount", movements.size()), at);
        return mapper.findReturn(principal.tenantId(), command.purchaseReturnId());
    }

    @Transactional(readOnly = true)
    public OrderDetail orderDetail(String orderId) {
        ProcurementRules.ulid(orderId, "orderId");
        String tenantId = tenantContext.requireTenantId();
        OrderHead head = mapper.findOrder(tenantId, orderId);
        if (head == null) throw new ServiceException("PUR-ORDER-001: 采购单不存在或不可见", 404);
        authorizationService.requireStoreAccess(head.storeId());
        return new OrderDetail(head, mapper.findOrderLines(tenantId, orderId));
    }

    @Transactional(readOnly = true)
    public ReceiptDetail receiptDetail(String receiptId) {
        ProcurementRules.ulid(receiptId, "receiptId");
        String tenantId = tenantContext.requireTenantId();
        ReceiptHead head = mapper.findReceipt(tenantId, receiptId);
        if (head == null) throw new ServiceException("PUR-RECEIPT-005: 收货单不存在或不可见", 404);
        authorizationService.requireStoreAccess(head.storeId());
        return new ReceiptDetail(head, mapper.findReceiptLines(tenantId, receiptId));
    }

    /** 为补货 Owner 提供供应商权威快照，调用方不能自行声明供应商有效。 */
    @Override
    @Transactional(readOnly = true)
    public SupplierSnapshot requireActiveSupplier(String supplierId) {
        ProcurementRules.ulid(supplierId, "supplierId");
        String tenantId = tenantContext.requireTenantId();
        Supplier supplier = mapper.findSupplier(tenantId, supplierId);
        if (supplier == null || !"ACTIVE".equals(supplier.status())) {
            throw new ServiceException("RPL-SUP-001: 供应商不存在、不可见或已停用", 409);
        }
        return new SupplierSnapshot(supplier.supplierId(), supplier.code(), supplier.name(), supplier.status());
    }

    /** 已确认在途量由采购 Owner 按冻结采购单位换算汇总。 */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal confirmedInTransitBase(String warehouseId, Long skuId, String supplierId) {
        ProcurementRules.ulid(warehouseId, "warehouseId");
        ProcurementRules.ulid(supplierId, "supplierId");
        if (skuId == null || skuId <= 0) {
            throw new ServiceException("RPL-INPUT-002: skuId 非法", 400);
        }
        BigDecimal value = mapper.sumConfirmedInTransitBase(tenantContext.requireTenantId(), warehouseId,
            skuId, supplierId);
        return value == null ? BigDecimal.ZERO.setScale(6) : value.setScale(6);
    }

    /**
     * 由已审批建议创建采购草稿。此端口只写 Procurement Owner 自有表，审批前不产生采购承诺、库存或成本效果。
     */
    @Override
    @Transactional
    public DraftResult createReplenishmentDraft(DraftCommand command) {
        ProcurementRules.ulid(command.purchaseOrderId(), "purchaseOrderId");
        ProcurementRules.ulid(command.suggestionId(), "suggestionId");
        ProcurementRules.ulid(command.orderLineId(), "orderLineId");
        ProcurementRules.ulid(command.supplierId(), "supplierId");
        ProcurementRules.ulid(command.warehouseId(), "warehouseId");
        commandPolicy.requireCorrelation(command.correlationId());
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        authorizationService.requireStoreAccess(command.storeId());
        Supplier supplier = mapper.lockSupplier(principal.tenantId(), command.supplierId());
        if (supplier == null || !"ACTIVE".equals(supplier.status())) {
            throw new ServiceException("RPL-SUP-001: 供应商不存在、不可见或已停用", 409);
        }
        SkuUnitSnapshot unit = catalogPort.requireUnit(command.skuId(), command.purchaseUnitId());
        if (unit.numerator() != command.conversionNumerator()
            || unit.denominator() != command.conversionDenominator()) {
            throw new ServiceException("RPL-UNIT-001: 采购单位换算已变化，建议必须过期重算", 409);
        }
        BigDecimal ordered = ProcurementRules.quantity(command.orderedQuantity(), "orderedQuantity");
        ProcurementRules.toBaseQuantity(ordered, unit.numerator(), unit.denominator());
        String requestHash = ProcurementHash.sha256(ProcurementHash.canonical(List.of(
            command.purchaseOrderId(), command.suggestionId(), command.supplierId(), command.storeId(),
            command.warehouseId(), command.expectedDate(), command.orderLineId(), command.skuId(),
            command.purchaseUnitId(), command.conversionNumerator(), command.conversionDenominator(),
            ordered, command.unitPriceMinor(), command.taxRateBps())));
        OrderHead existing = mapper.findOrder(principal.tenantId(), command.purchaseOrderId());
        if (existing != null) {
            if (!requestHash.equals(mapper.findOrderRequestHash(principal.tenantId(), command.purchaseOrderId()))) {
                throw new ServiceException("RPL-IDEM-003: 相同采购草稿标识对应不同建议内容", 409);
            }
            return new DraftResult(existing.orderId(), existing.status(), true);
        }
        LocalDateTime at = now();
        mapper.insertOrder(new OrderWrite(command.purchaseOrderId(), principal.tenantId(), command.supplierId(),
            command.storeId(), command.warehouseId(), command.expectedDate(), 0, "REPLENISHMENT",
            command.suggestionId(), requestHash, command.correlationId(), principal.userId(), at));
        mapper.insertOrderLine(new OrderLineWrite(command.orderLineId(), principal.tenantId(),
            command.purchaseOrderId(), command.skuId(), command.purchaseUnitId(), unit.numerator(),
            unit.denominator(), ordered, ProcurementRules.money(command.unitPriceMinor()),
            ProcurementRules.taxRate(command.taxRateBps()), at));
        audit(principal, command.storeId(), "REPLENISHMENT_DRAFT_CREATED", "PURCHASE_ORDER",
            command.purchaseOrderId(), command.suggestionId(), command.correlationId(), null, "DRAFT",
            "SOURCE_REPLENISHMENT", at);
        return new DraftResult(command.purchaseOrderId(), "DRAFT", false);
    }

    private OrderHead requireLockedOrder(String tenantId, String orderId) {
        OrderHead order = mapper.lockOrder(tenantId, orderId);
        if (order == null) throw new ServiceException("PUR-ORDER-001: 采购单不存在或不可见", 404);
        return order;
    }

    private void updateOrderState(OrderHead order, String next, Long approver, LocalDateTime approvedAt,
                                  LocalDateTime at) {
        if (mapper.updateOrderStatus(new OrderStatusUpdate(tenantContext.requireTenantId(), order.orderId(),
            order.status(), next, order.version(), approver, approvedAt, at)) != 1) {
            throw new ServiceException("PUR-ORDER-003: 采购单状态或版本冲突", 409);
        }
    }

    private void updateReturnState(ReturnHead value, String next, LocalDateTime at) {
        if (mapper.updateReturnState(new ReturnStateUpdate(tenantContext.requireTenantId(),
            value.purchaseReturnId(), value.status(), next, value.version(), at)) != 1) {
            throw new ServiceException("PUR-RETURN-005: 采购退货状态或版本冲突", 409);
        }
    }

    private void audit(TrustedPrincipal principal, Long storeId, String action, String aggregateType,
                       String aggregateId, String commandId, String correlationId,
                       Object before, Object after, String reason, LocalDateTime at) {
        String beforeText = before == null ? null : String.valueOf(before);
        String afterText = after == null ? null : String.valueOf(after);
        String hash = ProcurementHash.sha256(ProcurementHash.canonical(List.of(action, aggregateType,
            aggregateId, String.valueOf(beforeText), String.valueOf(afterText), reason)));
        mapper.insertAudit(new AuditWrite(ulids.next(), principal.tenantId(), storeId, action, aggregateType,
            aggregateId, principal.userId(), commandId, correlationId, beforeText, afterText, hash, reason, at));
    }

    private void event(String tenantId, String type, String aggregateId, long version,
                       String correlationId, Map<String, Object> payload, LocalDateTime at) {
        Map<String, Object> body = new LinkedHashMap<>(payload);
        body.put("schemaVersion", "1.0");
        body.put("correlationId", correlationId);
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PUR-EVENT-001: 采购事件序列化失败", 500);
        }
        mapper.insertOutbox(new OutboxWrite(ulids.next(), tenantId, type, aggregateId, version,
            correlationId, json, ProcurementHash.sha256(json), at));
    }
    private LocalDateTime now() { return LocalDateTime.now(clock.withZone(ZoneOffset.UTC)); }
}
