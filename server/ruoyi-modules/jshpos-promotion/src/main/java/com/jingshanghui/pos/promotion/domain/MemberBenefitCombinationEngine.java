package com.jingshanghui.pos.promotion.domain;

import com.jingshanghui.pos.catalog.application.model.MemberPriceModels.MemberPriceCandidate;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * T2-MEM-003 会员价与基础促销的确定性组合引擎。
 * 默认 BEST_PRICE，同额时选择普通促销；只有权益和促销配置双向允许时才叠加。
 */
@Component
public class MemberBenefitCombinationEngine {
    public enum Path { NORMAL_PATH, MEMBER_PATH, STACKED_MEMBER_PATH }

    public record MemberLine(BasketLine basket, Long unitId, MemberPriceCandidate candidate) { }
    public record Result(QuoteResult quote, Path path, String entitlementSnapshotId,
                         List<String> memberPriceVersionIds, List<Explanation> decisionChain) {
        public Result {
            memberPriceVersionIds = List.copyOf(memberPriceVersionIds);
            decisionChain = List.copyOf(decisionChain);
        }
    }

    /** 组合两条候选路径并重新校验逐行与头金额守恒。 */
    public Result combine(QuoteResult normal, List<MemberLine> memberLines, String entitlementSnapshotId,
                          boolean entitlementAllowsStacking, boolean promotionAllowsStacking) {
        requireNormal(normal);
        if (memberLines == null || memberLines.isEmpty() || entitlementSnapshotId == null) {
            return normal(normal, "MEMBER_BENEFIT_UNAVAILABLE", entitlementSnapshotId);
        }
        Map<String, QuoteLine> normalLines = new LinkedHashMap<>();
        normal.lines().forEach(line -> normalLines.put(line.lineId(), line));
        List<QuoteLine> memberQuoteLines = new ArrayList<>();
        List<AppliedAdjustment> memberAdjustments = new ArrayList<>();
        Set<String> versions = new TreeSet<>();
        long memberDiscount = 0;
        for (MemberLine input : memberLines) {
            if (input == null || input.basket() == null || input.unitId() == null || input.unitId() <= 0) {
                throw new ServiceException("PRM-MEMBER-001: 会员价组合行无效", 400);
            }
            QuoteLine normalLine = normalLines.get(input.basket().lineId());
            if (normalLine == null) throw new ServiceException("PRM-MEMBER-002: 会员价行与普通报价不一致", 409);
            long lineDiscount = 0;
            if (input.candidate() != null) {
                if (!input.candidate().skuId().equals(input.basket().skuId())
                    || !input.candidate().unitId().equals(input.unitId())
                    || !input.candidate().entitlementSnapshotId().equals(entitlementSnapshotId)
                    || !"CNY".equals(input.candidate().currency())) {
                    throw new ServiceException("PRM-MEMBER-003: 会员价候选身份不一致", 409);
                }
                long memberGross = exactLineAmount(input.candidate().amountMinor(), input.basket().quantity());
                lineDiscount = Math.max(0, normalLine.grossAmountMinor() - memberGross);
                versions.add(input.candidate().versionId());
                if (lineDiscount > 0) memberAdjustments.add(new AppliedAdjustment(
                    input.candidate().versionId(), lineDiscount, Map.of(input.basket().lineId(), lineDiscount)));
            }
            memberDiscount = Math.addExact(memberDiscount, lineDiscount);
            memberQuoteLines.add(new QuoteLine(normalLine.lineId(), normalLine.grossAmountMinor(), lineDiscount,
                normalLine.grossAmountMinor() - lineDiscount));
        }
        if (memberQuoteLines.size() != normal.lines().size()) {
            throw new ServiceException("PRM-MEMBER-002: 会员价行与普通报价不一致", 409);
        }
        QuoteResult member = quote(normal.grossAmountMinor(), memberDiscount, memberQuoteLines,
            memberAdjustments, List.of(new Explanation(entitlementSnapshotId, "MEMBER_PRICE_CANDIDATE")));
        if (entitlementAllowsStacking && promotionAllowsStacking) {
            QuoteResult stacked = stacked(normal, member, memberAdjustments, entitlementSnapshotId);
            return new Result(stacked, Path.STACKED_MEMBER_PATH, entitlementSnapshotId, List.copyOf(versions),
                List.of(new Explanation(entitlementSnapshotId, "STACKING_DOUBLE_OPT_IN")));
        }
        if (member.discountAmountMinor() > normal.discountAmountMinor()) {
            return new Result(member, Path.MEMBER_PATH, entitlementSnapshotId, List.copyOf(versions),
                List.of(new Explanation(entitlementSnapshotId, "BEST_PRICE_MEMBER_SELECTED")));
        }
        return normal(normal, member.discountAmountMinor() == normal.discountAmountMinor()
            ? "BEST_PRICE_TIE_NORMAL_SELECTED" : "BEST_PRICE_PROMOTION_SELECTED", entitlementSnapshotId);
    }

    /** 精确数量与最小货币单位相乘，统一 HALF_EVEN 到分。 */
    public long exactLineAmount(long unitPriceMinor, BigDecimal quantity) {
        if (unitPriceMinor < 0 || quantity == null || quantity.signum() <= 0 || quantity.scale() > 6) {
            throw new ServiceException("PRM-MEMBER-004: 会员价金额输入无效", 400);
        }
        return BigDecimal.valueOf(unitPriceMinor).multiply(quantity).setScale(0, RoundingMode.HALF_EVEN)
            .longValueExact();
    }

    private Result normal(QuoteResult quote, String code, String entitlementSnapshotId) {
        return new Result(quote, Path.NORMAL_PATH, entitlementSnapshotId, List.of(),
            List.of(new Explanation(entitlementSnapshotId == null ? "NON_MEMBER" : entitlementSnapshotId, code)));
    }

    private QuoteResult stacked(QuoteResult normal, QuoteResult member, List<AppliedAdjustment> memberAdjustments,
                                String entitlementSnapshotId) {
        Map<String, QuoteLine> memberByLine = new HashMap<>();
        member.lines().forEach(line -> memberByLine.put(line.lineId(), line));
        List<QuoteLine> lines = new ArrayList<>();
        Map<String, Long> remainingByLine = new HashMap<>();
        long discount = 0;
        for (QuoteLine normalLine : normal.lines()) {
            QuoteLine memberLine = memberByLine.get(normalLine.lineId());
            if (memberLine == null) {
                throw new ServiceException("PRM-MEMBER-002: 会员价行与普通报价不一致", 409);
            }
            long remaining = normalLine.grossAmountMinor() - normalLine.discountAmountMinor();
            long memberApplied = Math.min(remaining, memberLine.discountAmountMinor());
            remainingByLine.put(normalLine.lineId(), memberApplied);
            long combined = Math.addExact(normalLine.discountAmountMinor(), memberApplied);
            discount = Math.addExact(discount, combined);
            lines.add(new QuoteLine(normalLine.lineId(), normalLine.grossAmountMinor(), combined,
                normalLine.grossAmountMinor() - combined));
        }
        List<AppliedAdjustment> adjustments = new ArrayList<>(normal.adjustments());
        for (AppliedAdjustment memberAdjustment : memberAdjustments) {
            Map<String, Long> capped = new LinkedHashMap<>();
            long total = 0;
            for (Map.Entry<String, Long> allocation : memberAdjustment.lineAllocations().entrySet()) {
                long applied = Math.min(allocation.getValue(), remainingByLine.getOrDefault(allocation.getKey(), 0L));
                if (applied > 0) {
                    capped.put(allocation.getKey(), applied);
                    total = Math.addExact(total, applied);
                }
            }
            if (total > 0) adjustments.add(new AppliedAdjustment(memberAdjustment.sourceId(), total, capped));
        }
        List<Explanation> explanations = new ArrayList<>(normal.explanations());
        explanations.add(new Explanation(entitlementSnapshotId, "STACKING_APPLIED"));
        return quote(normal.grossAmountMinor(), discount, lines, adjustments, explanations);
    }

    private QuoteResult quote(long gross, long discount, List<QuoteLine> lines,
                              List<AppliedAdjustment> adjustments, List<Explanation> explanations) {
        long lineGross = lines.stream().mapToLong(QuoteLine::grossAmountMinor).reduce(0, Math::addExact);
        long lineDiscount = lines.stream().mapToLong(QuoteLine::discountAmountMinor).reduce(0, Math::addExact);
        long payable = Math.subtractExact(gross, discount);
        long linePayable = lines.stream().mapToLong(QuoteLine::payableAmountMinor).reduce(0, Math::addExact);
        long allocatedDiscount = adjustments.stream().mapToLong(AppliedAdjustment::amountMinor)
            .reduce(0, Math::addExact);
        if (gross < 0 || discount < 0 || discount > gross || lineGross != gross || lineDiscount != discount
            || linePayable != payable || allocatedDiscount != discount) {
            throw new ServiceException("PRM-MEMBER-005: 会员权益金额不守恒", 409);
        }
        List<String> applied = adjustments.stream().map(AppliedAdjustment::sourceId).distinct().toList();
        return new QuoteResult(gross, discount, payable, lines, applied, explanations, adjustments);
    }

    private void requireNormal(QuoteResult normal) {
        if (normal == null || normal.grossAmountMinor() < 0 || normal.discountAmountMinor() < 0
            || normal.payableAmountMinor() < 0
            || normal.grossAmountMinor() != normal.discountAmountMinor() + normal.payableAmountMinor()) {
            throw new ServiceException("PRM-MEMBER-006: 普通促销报价无效", 409);
        }
    }
}
