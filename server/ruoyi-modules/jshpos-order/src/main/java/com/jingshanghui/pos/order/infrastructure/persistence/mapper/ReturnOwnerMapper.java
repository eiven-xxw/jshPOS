package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import com.jingshanghui.pos.order.application.port.ReturnOrderSnapshotPort.ReturnOrderLine;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 退货只读快照与现金退款不可变事实 Mapper；复杂 SQL 只允许位于 XML。 */
public interface ReturnOwnerMapper {

    ReturnOrderHeader findReturnOrder(@Param("tenantId") String tenantId, @Param("orderId") String orderId);

    List<ReturnOrderHeader> findReturnOrders(@Param("tenantId") String tenantId,
                                             @Param("orderQuery") String orderQuery);

    List<ReturnOrderLine> listReturnOrderLines(@Param("tenantId") String tenantId,
                                               @Param("orderId") String orderId);

    CashPayment lockCashPayment(@Param("tenantId") String tenantId, @Param("cashPaymentId") String cashPaymentId);

    CashRefund findCashRefund(@Param("tenantId") String tenantId, @Param("refundId") String refundId);

    long sumSucceededCashRefund(@Param("tenantId") String tenantId,
                                @Param("cashPaymentId") String cashPaymentId);

    int insertCashRefund(@Param("cashRefundId") String cashRefundId, @Param("tenantId") String tenantId,
                         @Param("refundId") String refundId, @Param("orderId") String orderId,
                         @Param("cashPaymentId") String cashPaymentId, @Param("shiftId") String shiftId,
                         @Param("storeId") Long storeId, @Param("terminalId") String terminalId,
                         @Param("businessDate") LocalDate businessDate, @Param("amountMinor") long amountMinor,
                         @Param("requestSha256") String requestSha256, @Param("correlationId") String correlationId,
                         @Param("actorUserId") Long actorUserId, @Param("occurredAt") LocalDateTime occurredAt);

    int insertCashRefundLedger(@Param("ledgerId") String ledgerId, @Param("tenantId") String tenantId,
                               @Param("shiftId") String shiftId, @Param("orderId") String orderId,
                               @Param("cashPaymentId") String cashPaymentId,
                               @Param("cashRefundId") String cashRefundId,
                               @Param("amountMinor") long amountMinor,
                               @Param("businessDate") LocalDate businessDate,
                               @Param("occurredAt") LocalDateTime occurredAt);

    /** Order Owner头快照，promotion字段只作不可变引用。 */
    record ReturnOrderHeader(String orderId, String localOrderNo, Long storeId, String terminalId,
                             LocalDate businessDate,
                             String status, String paymentStatus, String currency, long grossAmountMinor,
                             long discountAmountMinor, long surchargeAmountMinor, long receivableAmountMinor,
                             String promotionSnapshotId, String promotionSnapshotSha256,
                             String cashPaymentId) { }

    /** 原现金收款锁定投影。 */
    record CashPayment(String cashPaymentId, String orderId, String status, long netAmountMinor) { }

    /** 已落账现金退款幂等投影。 */
    record CashRefund(String cashRefundId, String refundId, long amountMinor,
                      String requestSha256, String status) { }
}
