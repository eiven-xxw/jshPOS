package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderViews.CashOrderResult;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.PromotedLine;
import com.jingshanghui.pos.order.application.model.PromotedOrderCommands.SubmitPromotedCashOrder;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.BindingWrite;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.LineWrite;
import com.jingshanghui.pos.order.application.port.PromotedOrderRepository.OrderWrite;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort.Snapshot;
import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.OrderRules.CashAmounts;
import com.jingshanghui.pos.order.domain.OrderRules.PromotedLineAmount;
import com.jingshanghui.pos.order.domain.OrderRules.PromotedTotals;
import com.jingshanghui.pos.order.domain.PromotedOrderSnapshotCodec;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ORD-003 含促销现金订单应用服务。
 * 只验证 Promotion Owner 快照并保存引用，不复制或执行任何促销算法。
 */
@Service
@RequiredArgsConstructor
public class PromotedCashOrderService {

    private static final String COMMAND_TYPE = "SUBMIT_PROMOTED_CASH_ORDER";
    private final PromotedOrderRepository promotedOrders;
    private final OrderMapper orderMapper;
    private final PromotionSnapshotQueryPort promotionSnapshots;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final IdempotencyService idempotency;
    private final OrderJournalService journal;
    private final UlidGenerator ulids;

    /** 在一个服务端事务内保存订单、快照绑定、现金、审计、幂等与Outbox。 */
    @Transactional
    public CashOrderResult submit(SubmitPromotedCashOrder command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireActor(command.cashierId(), principal);
        authorizationService.requireStoreAccess(command.storeId());
        validateShape(command);
        PromotedTotals totals = OrderRules.validatePromotedOrder(command.lines().stream()
            .map(this::toAmount).toList(), command.grossAmountMinor(), command.discountAmountMinor(),
            command.surchargeAmountMinor(), command.receivableAmountMinor());
        String requestHash = CanonicalHash.sha256(canonicalCommand(command));
        CashOrderResult duplicate = idempotency.find(principal.tenantId(), COMMAND_TYPE,
            command.idempotencyKey(), requestHash, CashOrderResult.class);
        if (duplicate != null) {
            return duplicateResult(duplicate);
        }

        Snapshot promotion = promotionSnapshots.requireSnapshot(principal.tenantId(), command.promotionSnapshotId());
        verifyPromotionSnapshot(command, promotion);
        ShiftView shift = orderMapper.lockShift(principal.tenantId(), command.shiftId());
        requireUsableShift(command, principal, shift);
        CashAmounts cash = OrderRules.cash(totals.receivableMinor(), command.tenderedAmountMinor());
        LocalDateTime at = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        CanonicalJson.Result orderSnapshot = PromotedOrderSnapshotCodec.encode(command, principal.userId());
        if (!orderSnapshot.sha256().equals(command.orderSnapshotSha256())) {
            throw conflict("ORDER_SNAPSHOT_HASH_MISMATCH", "POS订单快照摘要不一致");
        }

        promotedOrders.insertOrder(new OrderWrite(principal.tenantId(), command.orderId(), command.localOrderNo(),
            command.storeId(), command.terminalId(), command.shiftId(), principal.userId(), command.businessDate(),
            command.storeTimezone(), totals.grossMinor(), totals.discountMinor(), totals.surchargeMinor(),
            totals.receivableMinor(), command.catalogVersion(), command.priceVersion(),
            command.industryTemplateVersion(), orderSnapshot.json(), orderSnapshot.sha256(),
            command.idempotencyKey(), requestHash, at));
        command.lines().stream().sorted(Comparator.comparingInt(PromotedLine::lineNo)).forEach(line ->
            promotedOrders.insertLine(new LineWrite(principal.tenantId(), command.orderId(), line.lineId(),
                line.lineNo(), line.skuId(), line.skuCode(), line.barcode(), line.productName(), line.unitId(),
                line.unitCode(), OrderRules.requireQuantity(line.quantity()), line.unitPriceMinor(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.surchargeAmountMinor(),
                line.payableAmountMinor(), line.priceSource())));
        promotedOrders.insertPromotionBinding(new BindingWrite(ulids.next(), principal.tenantId(), command.orderId(),
            command.promotionSnapshotId(), promotion.quoteId(), command.storeId(), command.terminalId(),
            command.businessDate(), command.quoteFingerprint(), command.settlementFingerprint(),
            command.promotionPackageVersion(), command.promotionSnapshotSha256(), command.orderSnapshotSha256(),
            totals.grossMinor(), totals.discountMinor(), totals.surchargeMinor(), totals.receivableMinor(),
            command.commandId(), at));
        appendStateHistory(principal, command, at);
        String paymentId = ulids.next();
        orderMapper.insertCashPayment(principal.tenantId(), paymentId, command.orderId(), command.shiftId(),
            cash.netMinor(), cash.tenderedMinor(), cash.changeMinor(), cash.netMinor(), at);
        orderMapper.insertCashLedger(principal.tenantId(), ulids.next(), command.shiftId(), command.orderId(),
            paymentId, cash.netMinor(), command.businessDate(), at);
        if (orderMapper.addShiftCash(principal.tenantId(), command.shiftId(), cash.netMinor()) != 1) {
            throw conflict("SHIFT_STATE_CONFLICT", "现金写入时班次不再可用");
        }
        orderMapper.insertPrintJob(principal.tenantId(), ulids.next(), command.orderId(),
            command.industryTemplateVersion(), orderSnapshot.sha256(), at);
        appendEvents(principal.tenantId(), command, paymentId, cash, at);
        CashOrderResult result = new CashOrderResult(command.orderId(), paymentId, "COMPLETED", "PAID",
            totals.receivableMinor(), cash.tenderedMinor(), cash.changeMinor(), "CNY",
            "sha256:" + orderSnapshot.sha256(), 4, command.commandId(), false);
        journal.audit(principal.tenantId(), "PROMOTED_CASH_ORDER_COMPLETED", "ORDER", command.orderId(),
            principal.userId(), null, command.commandId(), "DRAFT", "COMPLETED", totals.receivableMinor(),
            requestHash, "PROMOTED_CASH_SALE", at);
        idempotency.save(principal.tenantId(), COMMAND_TYPE, command.commandId(), command.idempotencyKey(),
            requestHash, command.orderId(), result, at);
        return result;
    }

    private PromotedLineAmount toAmount(PromotedLine line) {
        return new PromotedLineAmount(line.lineId(), line.lineNo(), line.skuId(), line.quantity(),
            line.unitPriceMinor(), line.grossAmountMinor(), line.discountAmountMinor(),
            line.surchargeAmountMinor(), line.payableAmountMinor(), line.priceSource(), line.sourceAllocations());
    }

    private void validateShape(SubmitPromotedCashOrder command) {
        OrderRules.requireUlid(command.commandId(), "commandId");
        OrderRules.requireUlid(command.orderId(), "orderId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        OrderRules.requireUlid(command.promotionSnapshotId(), "promotionSnapshotId");
        OrderRules.requireIdempotencyKey(command.idempotencyKey());
        if (command.localOrderNo() == null || command.localOrderNo().isBlank() || command.localOrderNo().length() > 40
            || command.businessDate() == null || command.storeTimezone() == null || command.storeTimezone().isBlank()
            || command.catalogVersion() <= 0 || command.priceVersion() <= 0
            || command.industryTemplateVersion() == null || command.industryTemplateVersion().isBlank()
            || command.promotionPackageVersion() <= 0 || command.occurredAt() == null
            || !isHash(command.promotionSnapshotSha256()) || !isHash(command.orderSnapshotSha256())
            || !isHash(command.quoteFingerprint()) || !isHash(command.settlementFingerprint())
            || command.manualEventRefs() == null || command.manualEventRefs().size() > 50
            || new HashSet<>(command.manualEventRefs()).size() != command.manualEventRefs().size()) {
            throw new ServiceException("ORDER_INPUT_INVALID: 含促销订单上下文不完整", 400);
        }
        command.manualEventRefs().forEach(value -> OrderRules.requireUlid(value, "manualEventRef"));
    }

    private void verifyPromotionSnapshot(SubmitPromotedCashOrder command, Snapshot snapshot) {
        if (!snapshot.snapshotId().equals(command.promotionSnapshotId())
            || !snapshot.orderId().equals(command.orderId()) || !snapshot.storeId().equals(command.storeId())
            || !snapshot.terminalId().equals(command.terminalId())
            || !snapshot.businessDate().equals(command.businessDate()) || !"CNY".equals(snapshot.currency())
            || !snapshot.snapshotSha256().equals(command.promotionSnapshotSha256())
            || !snapshot.quoteFingerprint().equals(command.quoteFingerprint())
            || !snapshot.settlementFingerprint().equals(command.settlementFingerprint())
            || snapshot.packageVersion() != command.promotionPackageVersion()
            || snapshot.grossAmountMinor() != command.grossAmountMinor()
            || snapshot.discountAmountMinor() != command.discountAmountMinor()
            || snapshot.payableAmountMinor() + command.surchargeAmountMinor() != command.receivableAmountMinor()) {
            throw conflict("PROMOTION_SNAPSHOT_MISMATCH", "促销快照身份、上下文、摘要或金额不一致");
        }
        Map<String, PromotionSnapshotQueryPort.Line> expected = new HashMap<>();
        snapshot.lines().forEach(line -> expected.put(line.lineId(), line));
        if (expected.size() != snapshot.lines().size() || expected.size() != command.lines().size()) {
            throw conflict("PROMOTION_SNAPSHOT_MISMATCH", "促销快照行数或行身份不一致");
        }
        for (PromotedLine line : command.lines()) {
            PromotionSnapshotQueryPort.Line source = expected.get(line.lineId());
            String allocationSha256 = CanonicalJson.from(new LinkedHashMap<>(line.sourceAllocations())).sha256();
            if (source == null || source.lineNo() != line.lineNo() || !source.skuId().equals(line.skuId())
                || source.quantity().compareTo(OrderRules.requireQuantity(line.quantity())) != 0
                || source.grossAmountMinor() != line.grossAmountMinor()
                || source.discountAmountMinor() != line.discountAmountMinor()
                || source.payableAmountMinor() + line.surchargeAmountMinor() != line.payableAmountMinor()
                || !source.sourceAllocationsSha256().equals(allocationSha256)) {
                throw conflict("PROMOTION_SNAPSHOT_MISMATCH", "订单行与促销成交快照不一致");
            }
        }
    }

    private void requireUsableShift(SubmitPromotedCashOrder command, TrustedPrincipal principal, ShiftView shift) {
        if (shift == null || !"OPEN".equals(shift.status()) || !shift.storeId().equals(command.storeId())
            || !shift.terminalId().equals(command.terminalId()) || !shift.cashierUserId().equals(principal.userId())
            || !shift.businessDate().equals(command.businessDate())
            || !shift.storeTimezone().equals(command.storeTimezone())) {
            throw conflict("SHIFT_NOT_OPEN", "当前可信门店终端没有匹配的OPEN班次");
        }
    }

    private void appendStateHistory(TrustedPrincipal principal, SubmitPromotedCashOrder command, LocalDateTime at) {
        String[] from = {null, "DRAFT", "PENDING_PAYMENT", "CONFIRMED"};
        String[] to = {"DRAFT", "PENDING_PAYMENT", "CONFIRMED", "COMPLETED"};
        for (int index = 0; index < to.length; index++) {
            if (from[index] != null) {
                OrderRules.requireTransition(from[index], to[index]);
            }
            orderMapper.insertStateHistory(principal.tenantId(), ulids.next(), command.orderId(), command.commandId(),
                from[index], to[index], index + 1L, principal.userId(), "PROMOTED_CASH_SALE", at);
        }
    }

    private void appendEvents(String tenantId, SubmitPromotedCashOrder command, String paymentId,
                              CashAmounts cash, LocalDateTime at) {
        Map<String, Object> submitted = eventAmounts(command);
        submitted.put("schemaVersion", "2.0");
        submitted.put("orderId", command.orderId());
        submitted.put("shiftId", command.shiftId());
        submitted.put("promotionSnapshotId", command.promotionSnapshotId());
        submitted.put("promotionSnapshotHash", "sha256:" + command.promotionSnapshotSha256());
        submitted.put("quoteFingerprint", command.quoteFingerprint());
        submitted.put("settlementFingerprint", command.settlementFingerprint());
        submitted.put("packageVersion", command.promotionPackageVersion());
        submitted.put("orderSnapshotHash", "sha256:" + command.orderSnapshotSha256());
        journal.appendEvent(tenantId, "order.command", "order.submitted.v2", "ORDER", command.orderId(), 2,
            command.commandId(), CanonicalJson.from(submitted).json(), at);

        Map<String, Object> cashEvent = new LinkedHashMap<>();
        cashEvent.put("paymentId", paymentId); cashEvent.put("orderId", command.orderId());
        cashEvent.put("shiftId", command.shiftId()); cashEvent.put("currency", "CNY");
        cashEvent.put("tenderedAmountMinor", cash.tenderedMinor());
        cashEvent.put("changeAmountMinor", cash.changeMinor()); cashEvent.put("netAmountMinor", cash.netMinor());
        journal.appendEvent(tenantId, "order.command", "cash.received.v1", "CASH_PAYMENT", paymentId, 1,
            command.commandId(), CanonicalJson.from(cashEvent).json(), at);

        Map<String, Object> completed = new LinkedHashMap<>(submitted);
        completed.put("paymentId", paymentId); completed.put("businessDate", command.businessDate().toString());
        completed.put("currency", "CNY"); completed.put("aggregateVersion", 4);
        journal.appendEvent(tenantId, "order.command", "order.completed.v2", "ORDER", command.orderId(), 4,
            command.commandId(), CanonicalJson.from(completed).json(), at);
    }

    private Map<String, Object> eventAmounts(SubmitPromotedCashOrder command) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("grossAmountMinor", command.grossAmountMinor());
        values.put("discountAmountMinor", command.discountAmountMinor());
        values.put("surchargeAmountMinor", command.surchargeAmountMinor());
        values.put("receivableAmountMinor", command.receivableAmountMinor());
        return values;
    }

    private String canonicalCommand(SubmitPromotedCashOrder command) {
        List<Object> values = new ArrayList<>();
        values.addAll(List.of(command.orderId(), command.localOrderNo(), command.storeId(), command.terminalId(),
            command.shiftId(), command.cashierId(), command.businessDate(), command.storeTimezone(),
            command.catalogVersion(), command.priceVersion(), command.industryTemplateVersion(),
            command.promotionSnapshotId(), command.promotionSnapshotSha256(), command.quoteFingerprint(),
            command.settlementFingerprint(), command.promotionPackageVersion(), command.orderSnapshotSha256(),
            command.grossAmountMinor(), command.discountAmountMinor(), command.surchargeAmountMinor(),
            command.receivableAmountMinor(), command.tenderedAmountMinor()));
        values.addAll(command.manualEventRefs());
        command.lines().stream().sorted(Comparator.comparingInt(PromotedLine::lineNo)).forEach(line -> {
            values.add(line.lineId()); values.add(line.lineNo()); values.add(line.skuId()); values.add(line.skuCode());
            values.add(line.barcode()); values.add(line.productName()); values.add(line.unitId());
            values.add(line.unitCode()); values.add(OrderRules.requireQuantity(line.quantity()).toPlainString());
            values.add(line.unitPriceMinor()); values.add(line.grossAmountMinor()); values.add(line.discountAmountMinor());
            values.add(line.surchargeAmountMinor()); values.add(line.payableAmountMinor()); values.add(line.priceSource());
            values.add(CanonicalJson.from(new LinkedHashMap<>(line.sourceAllocations())).json());
        });
        return CanonicalHash.lengthPrefixed(values);
    }

    private CashOrderResult duplicateResult(CashOrderResult value) {
        return new CashOrderResult(value.orderId(), value.paymentId(), value.status(), value.paymentStatus(),
            value.receivableAmountMinor(), value.tenderedAmountMinor(), value.changeAmountMinor(), value.currency(),
            value.snapshotHash(), value.recordVersion(), value.traceId(), true);
    }

    private void requireActor(String cashierId, TrustedPrincipal principal) {
        if (cashierId == null || !cashierId.equals(principal.userId().toString())) {
            throw new ServiceException("PERMISSION_DENIED: cashierId必须匹配可信操作者", 403);
        }
    }

    private boolean isHash(String value) {
        return value != null && value.matches("^[a-f0-9]{64}$");
    }

    private ServiceException conflict(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }
}
