package com.jingshanghui.pos.promotion.application.model;

import com.jingshanghui.pos.promotion.domain.MemberBenefitCombinationEngine.Path;
import com.jingshanghui.pos.promotion.domain.PromotionModels.BasketLine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.QuoteResult;

import java.time.OffsetDateTime;
import java.util.List;

/** T2-MEM-003 会员权益询价命令与无 PII 结果。 */
public final class MemberBenefitPromotionModels {
    /** 每行补充冻结销售单位，普通价格仍来自 BasketLine。 */
    public record MemberQuoteLine(BasketLine basket, Long unitId) { }
    /** tenant_id 不允许出现在命令中；权益快照只是不透明引用。 */
    public record MemberQuote(String pricingRequestId, Long storeId, String terminalId, String channel,
                              OffsetDateTime businessTime, String currency, long packageVersion,
                              String entitlementSnapshotId, List<MemberQuoteLine> lines, String correlationId) {
        public MemberQuote { lines = lines == null ? List.of() : List.copyOf(lines); }
    }
    /** 结果显式返回选择路径、权益/会员价版本与解释链摘要。 */
    public record MemberQuoteView(String quoteId, String requestSha256, String resultSha256,
                                  String engineVersion, long packageVersion, Path selectedPath,
                                  String entitlementSnapshotId, String benefitVersionId,
                                  List<String> memberPriceVersionIds, String rightsDigest,
                                  String explanationSha256, QuoteResult result) {
        public MemberQuoteView { memberPriceVersionIds = List.copyOf(memberPriceVersionIds); }
    }
    private MemberBenefitPromotionModels() { }
}
