package com.jingshanghui.pos.returns.application.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 换货 Saga 对 API、协调器和审计工作台暴露的只读投影。 */
public final class ExchangeViews {
    private ExchangeViews() { }

    /** 两条腿冻结引用；金额为各 Owner 独立金额，不得视为净额资金事实。 */
    public record ExchangeLegView(String legId, String legType, String ownerCode,
                                  String ownerAggregateId, String ownerCommandId,
                                  long expectedAmountMinor, String frozenSha256) { }

    /** 可恢复换货检查点。 */
    public record ExchangeView(String exchangeId, String returnId, String originalOrderId,
                               String originalReturnCommandId, String newOrderId, String newSaleCommandId,
                               Long storeId, String terminalId, LocalDate businessDate, String currency,
                               long expectedRefundAmountMinor, Long actualRefundAmountMinor,
                               long expectedSaleReceivableMinor, Long actualSaleReceivableMinor,
                               long displayDifferenceMinor, String quoteFingerprint,
                               String newSalePlanSha256, String actualNewOrderSnapshotSha256,
                               String status, Long requesterUserId, Long approverUserId, String reasonCode,
                               String correlationId, long recordVersion, List<ExchangeLegView> legs,
                               LocalDateTime updatedAt, boolean duplicate) {
        public ExchangeView { legs = List.copyOf(legs); }
    }
}
