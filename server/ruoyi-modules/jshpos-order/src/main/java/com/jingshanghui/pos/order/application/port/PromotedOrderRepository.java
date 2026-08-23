package com.jingshanghui.pos.order.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** ORD-003 不可变订单与促销绑定的 XML-only 仓储端口。 */
public interface PromotedOrderRepository {

    void insertOrder(OrderWrite value);

    void insertLine(LineWrite value);

    void insertPromotionBinding(BindingWrite value);

    void insertMemberBenefitBinding(MemberBenefitBindingWrite value);

    /** 含促销订单头写入参数。 */
    record OrderWrite(String tenantId, String orderId, String localOrderNo, Long storeId, String terminalId,
                      String shiftId, Long cashierUserId, LocalDate businessDate, String storeTimezone,
                      long grossAmountMinor, long discountAmountMinor, long surchargeAmountMinor,
                      long receivableAmountMinor, long catalogVersion, long priceVersion,
                      String industryTemplateVersion, String snapshotJson, String snapshotSha256,
                      String idempotencyKey, String requestSha256, LocalDateTime occurredAt) {
    }

    /** 含促销订单行写入参数。 */
    record LineWrite(String tenantId, String orderId, String lineId, int lineNo, Long skuId, String skuCode,
                     String barcode, String productName, Long unitId, String unitCode, BigDecimal quantity,
                     long unitPriceMinor, long grossAmountMinor, long discountAmountMinor,
                     long surchargeAmountMinor, long payableAmountMinor, String priceSource,
                     Long measurementTemplateId, Integer measurementTemplateVersion,
                     String measurementTemplateSha256, String measurementParseSha256,
                     String measurementSnapshotJson) {
    }

    /** Order Owner 保存的不可变促销快照身份与核验结果。 */
    record BindingWrite(String bindingId, String tenantId, String orderId, String promotionSnapshotId,
                        String quoteId, Long storeId, String terminalId, LocalDate businessDate,
                        String quoteFingerprint, String settlementFingerprint, long packageVersion,
                        String promotionSnapshotSha256, String orderSnapshotSha256, long grossAmountMinor,
                        long discountAmountMinor, long surchargeAmountMinor, long receivableAmountMinor,
                        String correlationId, LocalDateTime createdAt) {
    }

    /** Order Owner 对原会员权益、会员价与促销路径的不可变无 PII 成交绑定。 */
    record MemberBenefitBindingWrite(String bindingId, String tenantId, String orderId,
                                     String promotionSnapshotId, String quoteId,
                                     String entitlementSnapshotId, String benefitVersionId,
                                     String selectedPath, String memberPriceVersionsJson,
                                     long capabilityConfigVersion, String capabilitySha256,
                                     String rightsDigest, String explanationSha256,
                                     String promotionBindingSha256, LocalDateTime createdAt) { }
}
