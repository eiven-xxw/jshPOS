package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Order Owner 读取 Promotion Owner 不可变成交快照的受控端口。 */
public interface PromotionSnapshotQueryPort {

    /**
     * 在可信租户内读取指定成交快照；不存在或不可见时必须失败关闭。
     *
     * @param tenantId 服务端可信租户
     * @param snapshotId 促销成交快照ULID
     * @return 不可变快照投影
     */
    Snapshot requireSnapshot(String tenantId, String snapshotId);

    /** 订单核验所需的促销快照头与行。 */
    record Snapshot(String snapshotId, String orderId, String quoteId, Long storeId, String terminalId,
                    LocalDate businessDate, String currency, String quoteFingerprint,
                    String settlementFingerprint, long packageVersion, String snapshotSha256,
                    long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                    List<Line> lines, MemberBenefit memberBenefit) {
        public Snapshot {
            lines = List.copyOf(lines);
        }

        /** 兼容不含会员权益的既有成交快照。 */
        public Snapshot(String snapshotId, String orderId, String quoteId, Long storeId, String terminalId,
                        LocalDate businessDate, String currency, String quoteFingerprint,
                        String settlementFingerprint, long packageVersion, String snapshotSha256,
                        long grossAmountMinor, long discountAmountMinor, long payableAmountMinor,
                        List<Line> lines) {
            this(snapshotId, orderId, quoteId, storeId, terminalId, businessDate, currency, quoteFingerprint,
                settlementFingerprint, packageVersion, snapshotSha256, grossAmountMinor, discountAmountMinor,
                payableAmountMinor, lines, null);
        }
    }

    /** 订单核验所需的促销成交行。 */
    record Line(String lineId, int lineNo, Long skuId, BigDecimal quantity, long grossAmountMinor,
                long discountAmountMinor, long payableAmountMinor, String sourceAllocationsSha256) {
    }

    /** Promotion Owner 提供的无 PII 会员权益路径冻结投影。 */
    record MemberBenefit(String entitlementSnapshotId, String benefitVersionId, String selectedPath,
                         String memberPriceVersionsJson, long capabilityConfigVersion,
                         String capabilitySha256, String rightsDigest, String explanationSha256,
                         String contentSha256) { }
}
