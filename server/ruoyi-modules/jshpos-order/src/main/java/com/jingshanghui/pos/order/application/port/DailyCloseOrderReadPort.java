package com.jingshanghui.pos.order.application.port;

import java.time.LocalDate;

/** Operations 日结读取 Shift/Order Owner 权威事实的窄端口。 */
public interface DailyCloseOrderReadPort {
    DailyOrderFacts read(Long storeId, LocalDate businessDate);

    /** 所有金额均为最小货币单位整数；该快照只读且不包含租户输入。 */
    record DailyOrderFacts(long orderCount, long cancelledOrderCount, long refundCount,
                           long grossMinor, long discountMinor, long surchargeMinor,
                           long receivableMinor, long refundMinor, long cashReceivedMinor,
                           long cashRefundedMinor, long shiftDifferenceMinor,
                           long openShiftCount, long unapprovedCashDifferenceCount,
                           long sourceVersion, String currency) {
    }
}
