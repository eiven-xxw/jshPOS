package com.jingshanghui.pos.reporting.application.port;

import java.time.LocalDate;

/** Operations 日结读取可重建投影和来源血缘健康度的窄端口。 */
public interface DailyCloseReportingReadPort {
    DailyReportingFacts read(Long storeId, LocalDate businessDate);

    record DailyReportingFacts(long orderCount, long cancelledOrderCount, long returnCount,
                               long grossMinor, long discountMinor, long surchargeMinor,
                               long receivableMinor, long refundMinor, long cashReceivedMinor,
                               long cashRefundedMinor, long shiftDifferenceMinor,
                               long incompleteLineageCount, long openDifferenceCount,
                               long lineageOwnerCount, long maximumSourceSequence,
                               String salesProjectionVersion, String inventoryProjectionVersion,
                               String currency) {
    }
}
