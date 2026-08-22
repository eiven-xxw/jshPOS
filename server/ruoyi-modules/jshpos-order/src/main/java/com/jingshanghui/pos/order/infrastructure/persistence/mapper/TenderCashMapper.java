package com.jingshanghui.pos.order.infrastructure.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 部分现金份额持久化边界；复杂锁与只追加写入全部位于 XML。 */
public interface TenderCashMapper {

    CashTenderRow findByAllocation(@Param("tenantId") String tenantId,
                                   @Param("allocationId") String allocationId);

    int insertCashTender(@Param("tenantId") String tenantId, @Param("cashTenderId") String cashTenderId,
                         @Param("planId") String planId, @Param("allocationId") String allocationId,
                         @Param("orderId") String orderId, @Param("shiftId") String shiftId,
                         @Param("storeId") Long storeId, @Param("terminalId") String terminalId,
                         @Param("cashierId") Long cashierId, @Param("businessDate") LocalDate businessDate,
                         @Param("amountMinor") long amountMinor, @Param("tenderedMinor") long tenderedMinor,
                         @Param("changeMinor") long changeMinor, @Param("requestHash") String requestHash,
                         @Param("correlationId") String correlationId, @Param("occurredAt") LocalDateTime occurredAt);

    int insertCashLedger(@Param("tenantId") String tenantId, @Param("ledgerId") String ledgerId,
                         @Param("shiftId") String shiftId, @Param("orderId") String orderId,
                         @Param("cashTenderId") String cashTenderId, @Param("amountMinor") long amountMinor,
                         @Param("businessDate") LocalDate businessDate,
                         @Param("occurredAt") LocalDateTime occurredAt);

    /** 现金份额不可变只读行。 */
    record CashTenderRow(String cashTenderId, String allocationId, String orderId, String shiftId,
                         long amountMinor, long tenderedMinor, long changeMinor, String requestSha256) {
    }
}
