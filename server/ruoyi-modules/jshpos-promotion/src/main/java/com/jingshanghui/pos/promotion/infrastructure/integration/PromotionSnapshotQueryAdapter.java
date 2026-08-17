package com.jingshanghui.pos.promotion.infrastructure.integration;

import com.jingshanghui.pos.order.application.port.PromotionSnapshotQueryPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredQuote;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredSnapshot;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

/**
 * Promotion Owner 对 Order Owner 暴露的只读快照适配器。
 * 不返回 Mapper，不允许 Order Owner 写入任何 prm_* 表。
 */
@Component
@RequiredArgsConstructor
public class PromotionSnapshotQueryAdapter implements PromotionSnapshotQueryPort {

    private final PromotionPersistencePort persistence;

    @Override
    public Snapshot requireSnapshot(String tenantId, String snapshotId) {
        StoredSnapshot snapshot = persistence.lockSnapshot(tenantId, snapshotId);
        if (snapshot == null) {
            throw new ServiceException("PROMOTION_SNAPSHOT_NOT_VISIBLE: 促销成交快照不存在或不可见", 404);
        }
        StoredQuote quote = persistence.findQuote(tenantId, snapshot.quoteId());
        if (quote == null) {
            throw new ServiceException("PROMOTION_SNAPSHOT_CORRUPTED: 原促销报价不存在", 409);
        }
        var lines = persistence.listSnapshotLines(tenantId, snapshot.snapshotId()).stream()
            .map(line -> new Line(line.lineId(), line.lineNo(), line.skuId(), line.quantity(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor(),
                line.sourceAllocationsSha256()))
            .toList();
        return new Snapshot(snapshot.snapshotId(), snapshot.orderId(), snapshot.quoteId(), snapshot.storeId(),
            snapshot.terminalId(), snapshot.businessDate(), snapshot.currency(), quote.resultSha256(),
            snapshot.quoteFingerprint(), quote.packageVersion(), snapshot.snapshotSha256(),
            snapshot.grossAmountMinor(), snapshot.discountAmountMinor(), snapshot.payableAmountMinor(), lines);
    }
}
