package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.application.service.PriceBookService;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.model.OrderCommands.CashOrder;
import com.jingshanghui.pos.order.application.model.OrderCommands.Line;
import com.jingshanghui.pos.order.application.model.OrderViews.CashOrderResult;
import com.jingshanghui.pos.order.application.model.OrderViews.OrderView;
import com.jingshanghui.pos.order.application.model.OrderViews.ShiftView;
import com.jingshanghui.pos.order.domain.CanonicalHash;
import com.jingshanghui.pos.order.domain.OrderRules;
import com.jingshanghui.pos.order.domain.OrderRules.CashAmounts;
import com.jingshanghui.pos.order.domain.OrderRules.LineAmount;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CashOrderService {

    private static final String COMMAND_TYPE = "SUBMIT_CASH_ORDER";

    private final OrderMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final PriceBookService priceBookService;
    private final IdempotencyService idempotency;
    private final OrderJournalService journal;
    private final UlidGenerator ulids;
    private final Clock clock;

    @Transactional
    public CashOrderResult submit(CashOrder command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireActor(command.cashierId(), principal);
        authorizationService.requireStoreAccess(command.storeId());
        validateCommandShape(command);
        String requestHash = CanonicalHash.sha256(canonical(command));
        CashOrderResult duplicate = idempotency.find(principal.tenantId(), COMMAND_TYPE,
            command.idempotencyKey(), requestHash, CashOrderResult.class);
        if (duplicate != null) {
            return new CashOrderResult(duplicate.orderId(), duplicate.paymentId(), duplicate.status(),
                duplicate.paymentStatus(), duplicate.receivableAmountMinor(), duplicate.tenderedAmountMinor(),
                duplicate.changeAmountMinor(), duplicate.currency(), duplicate.snapshotHash(),
                duplicate.recordVersion(), duplicate.traceId(), true);
        }
        List<LineAmount> lineAmounts = command.lines().stream().map(line -> new LineAmount(
            line.lineId(), line.lineNo(), line.skuId(), line.quantity(), line.unitPriceMinor(),
            line.grossAmountMinor(), line.payableAmountMinor(), line.priceSource())).toList();
        long total = OrderRules.validateOrder(lineAmounts, command.grossAmountMinor(), command.receivableAmountMinor());
        validateAuthoritativePrices(command);
        CashAmounts cash = OrderRules.cash(total, command.tenderedAmountMinor());
        ShiftView shift = mapper.lockShift(principal.tenantId(), command.shiftId());
        requireUsableShift(command, principal, shift);
        LocalDateTime at = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        String snapshotJson = snapshot(command, principal);
        String snapshotHash = CanonicalHash.sha256(snapshotJson);
        mapper.insertCompletedOrder(principal.tenantId(), command.orderId(), command.localOrderNo(), command.storeId(),
            command.terminalId(), command.shiftId(), principal.userId(), command.businessDate(), command.storeTimezone(),
            total, total, command.catalogVersion(), command.priceVersion(), command.industryTemplateVersion(),
            snapshotJson, snapshotHash, command.idempotencyKey(), requestHash, at);
        for (Line line : command.lines()) {
            mapper.insertOrderLine(principal.tenantId(), command.orderId(), line.lineId(), line.lineNo(), line.skuId(),
                line.skuCode(), line.barcode(), line.productName(), line.unitId(), line.unitCode(),
                OrderRules.requireQuantity(line.quantity()), line.unitPriceMinor(), line.grossAmountMinor(),
                line.payableAmountMinor(), line.priceSource());
        }
        appendStateHistory(principal, command, at);
        String paymentId = ulids.next();
        mapper.insertCashPayment(principal.tenantId(), paymentId, command.orderId(), command.shiftId(), total,
            cash.tenderedMinor(), cash.changeMinor(), cash.netMinor(), at);
        mapper.insertCashLedger(principal.tenantId(), ulids.next(), command.shiftId(), command.orderId(), paymentId,
            cash.netMinor(), command.businessDate(), at);
        if (mapper.addShiftCash(principal.tenantId(), command.shiftId(), cash.netMinor()) != 1) {
            throw new ServiceException("SHIFT_STATE_CONFLICT: 现金写入时班次不再可用", 409);
        }
        mapper.insertPrintJob(principal.tenantId(), ulids.next(), command.orderId(),
            command.industryTemplateVersion(), snapshotHash, at);
        appendEvents(principal.tenantId(), command, paymentId, cash, snapshotHash, at);
        String traceId = command.commandId();
        CashOrderResult result = new CashOrderResult(command.orderId(), paymentId, "COMPLETED", "PAID", total,
            cash.tenderedMinor(), cash.changeMinor(), "CNY", "sha256:" + snapshotHash, 4, traceId, false);
        journal.audit(principal.tenantId(), "CASH_ORDER_COMPLETED", "ORDER", command.orderId(), principal.userId(),
            null, command.commandId(), "DRAFT", "COMPLETED", total, requestHash, "CASH_SALE", at);
        idempotency.save(principal.tenantId(), COMMAND_TYPE, command.commandId(), command.idempotencyKey(),
            requestHash, command.orderId(), result, at);
        return result;
    }

    @Transactional(readOnly = true)
    public OrderView find(String orderId) {
        String tenantId = tenantContext.requireTenantId();
        OrderRules.requireUlid(orderId, "orderId");
        OrderView order = mapper.findOrder(tenantId, orderId);
        if (order == null) {
            throw new ServiceException("RESOURCE_NOT_VISIBLE: 订单不存在或不可见", 404);
        }
        authorizationService.requireStoreAccess(order.storeId());
        return order;
    }

    private void validateCommandShape(CashOrder command) {
        OrderRules.requireUlid(command.commandId(), "commandId");
        OrderRules.requireUlid(command.orderId(), "orderId");
        OrderRules.requireUlid(command.terminalId(), "terminalId");
        OrderRules.requireUlid(command.shiftId(), "shiftId");
        OrderRules.requireIdempotencyKey(command.idempotencyKey());
        if (command.localOrderNo() == null || command.localOrderNo().isBlank() || command.localOrderNo().length() > 40
            || command.businessDate() == null || command.storeTimezone() == null || command.storeTimezone().isBlank()
            || command.catalogVersion() <= 0 || command.priceVersion() <= 0
            || command.industryTemplateVersion() == null || command.industryTemplateVersion().isBlank()
            || command.occurredAt() == null) {
            throw new ServiceException("ORDER_INPUT_INVALID: 订单上下文不完整", 400);
        }
    }

    private void validateAuthoritativePrices(CashOrder command) {
        for (Line line : command.lines()) {
            ResolvedPrice price = priceBookService.resolve(line.skuId(), line.unitId(), command.storeId(),
                command.occurredAt());
            String source = "STORE".equals(price.scopeType()) ? "STORE_OVERRIDE" : "TENANT_BASE";
            if (price.amountMinor() != line.unitPriceMinor() || !source.equals(line.priceSource())) {
                throw new ServiceException("ORDER_AMOUNT_CHANGED: 订单价格与当前权威价格不一致", 409);
            }
        }
    }

    private void requireUsableShift(CashOrder command, TrustedPrincipal principal, ShiftView shift) {
        if (shift == null || !"OPEN".equals(shift.status()) || !shift.storeId().equals(command.storeId())
            || !shift.terminalId().equals(command.terminalId()) || !shift.cashierUserId().equals(principal.userId())
            || !shift.businessDate().equals(command.businessDate()) || !shift.storeTimezone().equals(command.storeTimezone())) {
            throw new ServiceException("SHIFT_NOT_OPEN: 当前可信门店终端没有匹配的 OPEN 班次", 409);
        }
    }

    private void appendStateHistory(TrustedPrincipal principal, CashOrder command, LocalDateTime at) {
        String[] from = {null, "DRAFT", "PENDING_PAYMENT", "CONFIRMED"};
        String[] to = {"DRAFT", "PENDING_PAYMENT", "CONFIRMED", "COMPLETED"};
        for (int index = 0; index < to.length; index++) {
            if (from[index] != null) {
                OrderRules.requireTransition(from[index], to[index]);
            }
            mapper.insertStateHistory(principal.tenantId(), ulids.next(), command.orderId(), command.commandId(),
                from[index], to[index], index + 1L, principal.userId(), "CASH_SALE", at);
        }
    }

    private void appendEvents(String tenantId, CashOrder command, String paymentId, CashAmounts cash,
                              String snapshotHash, LocalDateTime at) {
        journal.appendEvent(tenantId, "order.command", "order.submitted.v1", "ORDER", command.orderId(), 2,
            command.commandId(), CanonicalJson.from(Map.<String, Object>of("orderId", command.orderId(), "shiftId", command.shiftId(),
                "receivableAmountMinor", command.receivableAmountMinor(), "snapshotHash", "sha256:" + snapshotHash)).json(), at);
        journal.appendEvent(tenantId, "order.command", "cash.received.v1", "CASH_PAYMENT", paymentId, 1,
            command.commandId(), CanonicalJson.from(Map.<String, Object>of("paymentId", paymentId, "orderId", command.orderId(),
                "shiftId", command.shiftId(), "currency", "CNY", "tenderedAmountMinor", cash.tenderedMinor(),
                "changeAmountMinor", cash.changeMinor(), "netAmountMinor", cash.netMinor())).json(), at);
        journal.appendEvent(tenantId, "order.command", "order.completed.v1", "ORDER", command.orderId(), 4,
            command.commandId(), CanonicalJson.from(Map.<String, Object>of("orderId", command.orderId(), "shiftId", command.shiftId(),
                "paymentId", paymentId, "businessDate", command.businessDate().toString(), "currency", "CNY",
                "receivableAmountMinor", command.receivableAmountMinor(), "aggregateVersion", 4,
                "snapshotHash", "sha256:" + snapshotHash)).json(), at);
    }

    private String snapshot(CashOrder command, TrustedPrincipal principal) {
        List<Map<String, Object>> lines = new ArrayList<>();
        command.lines().stream().sorted(Comparator.comparingInt(Line::lineNo)).forEach(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lineId", line.lineId()); item.put("lineNo", line.lineNo());
            item.put("skuId", line.skuId().toString()); item.put("skuCode", line.skuCode());
            if (line.barcode() != null) item.put("barcode", line.barcode());
            item.put("productName", line.productName()); item.put("unitId", line.unitId().toString());
            item.put("unitCode", line.unitCode()); item.put("quantity", OrderRules.requireQuantity(line.quantity()).toPlainString());
            item.put("unitPriceMinor", line.unitPriceMinor()); item.put("grossAmountMinor", line.grossAmountMinor());
            item.put("discountAmountMinor", 0); item.put("surchargeAmountMinor", 0);
            item.put("payableAmountMinor", line.payableAmountMinor()); item.put("priceSource", line.priceSource());
            lines.add(item);
        });
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", 1); snapshot.put("orderId", command.orderId());
        snapshot.put("storeId", command.storeId().toString()); snapshot.put("terminalId", command.terminalId());
        snapshot.put("shiftId", command.shiftId()); snapshot.put("cashierId", principal.userId().toString());
        snapshot.put("businessDate", command.businessDate().toString()); snapshot.put("storeTimezone", command.storeTimezone());
        snapshot.put("currency", "CNY"); snapshot.put("grossAmountMinor", command.grossAmountMinor());
        snapshot.put("discountAmountMinor", 0); snapshot.put("surchargeAmountMinor", 0);
        snapshot.put("receivableAmountMinor", command.receivableAmountMinor());
        snapshot.put("catalogVersion", command.catalogVersion()); snapshot.put("priceVersion", command.priceVersion());
        snapshot.put("industryTemplateVersion", command.industryTemplateVersion()); snapshot.put("lines", lines);
        return CanonicalJson.from(snapshot).json();
    }

    private String canonical(CashOrder command) {
        List<Object> values = new ArrayList<>(14 + command.lines().size() * 13);
        values.addAll(List.of(command.orderId(), command.localOrderNo(), command.storeId(), command.terminalId(),
            command.shiftId(), command.cashierId(), command.businessDate(), command.storeTimezone(),
            command.catalogVersion(), command.priceVersion(), command.industryTemplateVersion(),
            command.grossAmountMinor(), command.receivableAmountMinor(), command.tenderedAmountMinor()));
        command.lines().stream().sorted(Comparator.comparingInt(Line::lineNo)).forEach(line -> {
            values.add(line.lineId()); values.add(line.lineNo()); values.add(line.skuId());
            values.add(line.skuCode()); values.add(line.barcode()); values.add(line.productName());
            values.add(line.unitId()); values.add(line.unitCode());
            values.add(OrderRules.requireQuantity(line.quantity()).toPlainString());
            values.add(line.unitPriceMinor()); values.add(line.grossAmountMinor());
            values.add(line.payableAmountMinor()); values.add(line.priceSource());
        });
        return CanonicalHash.lengthPrefixed(values);
    }

    private void requireActor(String cashierId, TrustedPrincipal principal) {
        if (cashierId == null || !cashierId.equals(principal.userId().toString())) {
            throw new ServiceException("PERMISSION_DENIED: cashierId 必须匹配可信操作者", 403);
        }
    }
}
