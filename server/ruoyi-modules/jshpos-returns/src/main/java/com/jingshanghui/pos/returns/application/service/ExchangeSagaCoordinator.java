package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort.ExchangeOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.OwnerObservation;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeView;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ExchangeStates.Status;
import com.jingshanghui.pos.returns.domain.ReturnHash;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 换货跨 Owner 协调器；每次最多推进一个既有 Owner 检查点。
 * 它只查询或重放原 returnId/newOrderId，对 UNKNOWN 绝不生成第二个业务命令。
 */
@Service
@RequiredArgsConstructor
public class ExchangeSagaCoordinator {
    private final ExchangeOrchestrationService exchanges;
    private final ReturnOrchestrationService returns;
    private final ReturnSagaCoordinator returnCoordinator;
    private final ExchangeOrderSnapshotPort orders;
    private final UlidGenerator ulids;
    private final Clock clock;

    /** 观察一次当前腿；未形成权威终态时保持原检查点。 */
    public ExchangeView processNext(String exchangeId) {
        ExchangeView exchange = exchanges.find(exchangeId);
        Status status = Status.valueOf(exchange.status());
        return switch (status) {
            case RETURN_PENDING, RETURN_UNKNOWN -> observeReturn(exchange);
            case SALE_PENDING, SALE_UNKNOWN -> observeSale(exchange);
            case DRAFT, APPROVED, RETURN_COMPLETED, COMPLETED, FAILED,
                 MANUAL_RECOVERY_REQUIRED, CLOSED -> exchange;
        };
    }

    private ExchangeView observeReturn(ExchangeView exchange) {
        ReturnView source = returns.find(exchange.returnId());
        if (!source.orderId().equals(exchange.originalOrderId())
            || !source.requestCommandId().equals(exchange.originalReturnCommandId())) {
            throw new ServiceException("EXG-COORD-001: 原退货身份发生冲突", 409);
        }
        if (canAdvanceReturnOwner(source.status())) {
            source = returnCoordinator.processNext(source.returnId());
        }
        String observed;
        if ("COMPLETED".equals(source.status())) observed = "COMPLETED";
        else if ("PAYMENT_UNKNOWN".equals(source.status())) observed = "PAYMENT_UNKNOWN";
        else if ("FAILED".equals(source.status())) observed = "FAILED";
        else return exchange;
        long amount = source.refundableAmountMinor() == null ? 0 : source.refundableAmountMinor();
        Instant now = clock.instant();
        String payloadHash = ReturnHash.sha256(ReturnHash.canonical(List.of(source.returnId(),
            source.requestCommandId(), source.status(), amount, source.recordVersion())));
        return exchanges.acceptReturn(new OwnerObservation(ulids.next(), exchange.exchangeId(),
            source.returnId(), observed, amount, null, payloadHash, now));
    }

    private ExchangeView observeSale(ExchangeView exchange) {
        ExchangeOrderSnapshot sale = orders.find(exchange.newOrderId());
        if (sale == null) return exchange;
        if (!sale.storeId().equals(exchange.storeId()) || !sale.terminalId().equals(exchange.terminalId())
            || !sale.businessDate().equals(exchange.businessDate()) || !"CNY".equals(sale.currency())) {
            throw new ServiceException("EXG-COORD-002: 新销售门店、终端、业务日或币种冲突", 409);
        }
        String observed;
        if ("COMPLETED".equals(sale.status()) && "PAID".equals(sale.paymentStatus())) {
            if (!exchange.quoteFingerprint().equals(sale.quoteFingerprint())
                && !exchange.quoteFingerprint().equals(sale.settlementFingerprint())) {
                throw new ServiceException("EXG-COORD-003: 新销售冻结报价指纹不一致", 409);
            }
            observed = "COMPLETED";
        } else if ("UNKNOWN".equals(sale.paymentStatus())) {
            observed = "UNKNOWN";
        } else if ("CANCELLED".equals(sale.status()) || "FAILED".equals(sale.status())) {
            observed = sale.status();
        } else {
            return exchange;
        }
        Instant now = clock.instant();
        String payloadHash = ReturnHash.sha256(ReturnHash.canonical(List.of(sale.orderId(), sale.status(),
            sale.paymentStatus(), sale.receivableAmountMinor(), sale.quoteFingerprint(),
            sale.settlementFingerprint(), sale.orderSnapshotSha256())));
        return exchanges.acceptSale(new OwnerObservation(ulids.next(), exchange.exchangeId(), sale.orderId(),
            observed, sale.receivableAmountMinor(), sale.orderSnapshotSha256(), payloadHash, now));
    }

    private boolean canAdvanceReturnOwner(String status) {
        return "PROMOTION_PENDING".equals(status) || "CASH_REFUND_PENDING".equals(status)
            || "PAYMENT_PENDING".equals(status) || "PAYMENT_UNKNOWN".equals(status)
            || "INVENTORY_PENDING".equals(status);
    }
}
