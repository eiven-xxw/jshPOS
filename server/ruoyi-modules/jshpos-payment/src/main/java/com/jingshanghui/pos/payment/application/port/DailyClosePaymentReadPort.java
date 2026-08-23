package com.jingshanghui.pos.payment.application.port;

import java.time.LocalDateTime;

/** Operations 日结读取 Provider 无关支付与退款事实的窄端口。 */
public interface DailyClosePaymentReadPort {
    DailyPaymentFacts read(Long storeId, LocalDateTime fromUtc, LocalDateTime toUtc);

    record DailyPaymentFacts(long succeededPaymentCount, long succeededPaymentMinor,
                             long succeededRefundCount, long succeededRefundMinor,
                             long unknownPaymentCount, long unknownRefundCount,
                             long sourceVersion, String currency) {
    }
}
