package com.jingshanghui.pos.catalog.application.price;

import com.jingshanghui.pos.catalog.domain.CatalogRules;
import org.dromara.common.core.exception.ServiceException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** 在确定时间点按 STORE > TENANT_BASE 解析已发布售价。 */
public final class PriceResolution {

    private PriceResolution() {
    }

    public static ResolvedPrice resolve(List<Candidate> candidates, Long storeId, Instant at) {
        if (at == null) {
            throw new ServiceException("CAT-PRC-002: 价格解析时间不能为空", 400);
        }
        return candidates.stream()
            .filter(candidate -> candidate.published())
            .filter(candidate -> candidate.activeAt(at))
            .filter(candidate -> candidate.scopeMatches(storeId))
            .sorted(Comparator.comparingInt((Candidate candidate) -> candidate.scopeRank(storeId))
                .thenComparingInt(Candidate::versionNo)
                .thenComparing(Candidate::effectiveFrom)
                .thenComparing(Candidate::priceItemId)
                .reversed())
            .findFirst()
            .map(candidate -> new ResolvedPrice(
                CatalogRules.requireMinorAmount(candidate.amountMinor()), candidate.currency(),
                candidate.priceBookId(), candidate.priceItemId(), candidate.scopeType(), candidate.effectiveFrom()
            ))
            .orElseThrow(() -> new ServiceException("CAT-PRC-003: 指定时间和门店无有效售价", 404));
    }

    public record Candidate(
        Long priceBookId,
        Long priceItemId,
        int versionNo,
        String scopeType,
        Long scopeStoreId,
        Long amountMinor,
        String currency,
        Instant effectiveFrom,
        Instant effectiveTo,
        boolean published
    ) {
        public Candidate {
            if (versionNo <= 0) {
                throw new ServiceException("CAT-PRC-008: 价格版本必须为正整数", 400);
            }
            if (!"TENANT_BASE".equals(scopeType) && !"STORE".equals(scopeType)) {
                throw new ServiceException("CAT-PRC-004: 价格范围无效", 400);
            }
            if (("STORE".equals(scopeType)) != (scopeStoreId != null)) {
                throw new ServiceException("CAT-PRC-005: 价格范围形状无效", 400);
            }
            if (effectiveFrom == null || effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
                throw new ServiceException("CAT-PRC-006: 价格生效窗口无效", 400);
            }
            if (!"CNY".equals(currency)) {
                throw new ServiceException("CAT-PRC-007: Gate 1 仅允许 CNY", 400);
            }
            CatalogRules.requireMinorAmount(amountMinor);
        }

        boolean activeAt(Instant at) {
            return !at.isBefore(effectiveFrom) && (effectiveTo == null || at.isBefore(effectiveTo));
        }

        boolean scopeMatches(Long storeId) {
            return "TENANT_BASE".equals(scopeType) || scopeStoreId.equals(storeId);
        }

        int scopeRank(Long storeId) {
            return "STORE".equals(scopeType) && scopeStoreId.equals(storeId) ? 2 : 1;
        }
    }

    public record ResolvedPrice(
        long amountMinor,
        String currency,
        Long priceBookId,
        Long priceItemId,
        String scopeType,
        Instant effectiveFrom
    ) {
    }
}
