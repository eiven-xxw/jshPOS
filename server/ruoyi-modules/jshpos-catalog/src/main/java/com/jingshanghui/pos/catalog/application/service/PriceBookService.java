package com.jingshanghui.pos.catalog.application.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceBookView;
import com.jingshanghui.pos.catalog.application.model.CatalogViews.PriceCandidateView;
import com.jingshanghui.pos.catalog.application.price.PriceResolution;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.Candidate;
import com.jingshanghui.pos.catalog.application.price.PriceResolution.ResolvedPrice;
import com.jingshanghui.pos.catalog.application.port.OrderPriceResolutionPort;
import com.jingshanghui.pos.catalog.domain.CatalogRules;
import com.jingshanghui.pos.catalog.infrastructure.persistence.mapper.CatalogMapper;
import com.jingshanghui.pos.foundation.application.audit.DomainAuditService;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PriceBookService implements OrderPriceResolutionPort {

    private final CatalogMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorizationService;
    private final DomainAuditService auditService;
    private final Clock clock;
    private final CatalogOutboxService outboxService;

    @Transactional
    public PriceBookView create(String code, String name, int versionNo, String scopeType, Long storeId) {
        String tenantId = tenantContext.requireTenantId();
        String scope = requireScope(scopeType, storeId);
        if (storeId != null) {
            authorizationService.requireStoreAccess(storeId);
        }
        if (versionNo <= 0) {
            throw new ServiceException("CAT-PRC-010: 价格版本必须为正整数", 400);
        }
        Long id = IdWorker.getId();
        mapper.insertPriceBook(tenantId, id, CatalogRules.requireCode(code, "CAT-PRC-011"),
            CatalogRules.requireName(name), versionNo, scope, storeId);
        PriceBookView result = requireBook(tenantId, id);
        auditService.append("PRICE_BOOK_CREATED", "PRICE_BOOK", id, null, result,
            Map.of("scope", scope, "versionNo", versionNo));
        return result;
    }

    @Transactional
    public Long addItem(Long bookId, Long skuId, Long unitId, Long amountMinor, Instant from, Instant to) {
        String tenantId = tenantContext.requireTenantId();
        PriceBookView book = requireDraft(tenantId, bookId);
        requireBookAccess(book);
        if (from == null || to != null && !to.isAfter(from)) {
            throw new ServiceException("CAT-PRC-012: 价格生效窗口无效", 400);
        }
        if (mapper.findProduct(tenantId, skuId) == null || mapper.findUnit(tenantId, unitId) == null) {
            throw new ServiceException("CAT-PRC-013: 商品或单位不存在", 404);
        }
        LocalDateTime fromUtc = LocalDateTime.ofInstant(from, ZoneOffset.UTC);
        LocalDateTime toUtc = to == null ? null : LocalDateTime.ofInstant(to, ZoneOffset.UTC);
        if (mapper.countPriceOverlap(tenantId, bookId, book.scopeType(), book.storeId(), skuId, unitId,
            fromUtc, toUtc) > 0) {
            throw new ServiceException("CAT-PRC-014: 同范围价格时间窗重叠", 409);
        }
        Long itemId = IdWorker.getId();
        mapper.insertPriceItem(tenantId, itemId, bookId, skuId, unitId,
            CatalogRules.requireMinorAmount(amountMinor), fromUtc, toUtc);
        auditService.append("PRICE_ITEM_ADDED", "PRICE_ITEM", itemId, null, null,
            Map.of("bookId", bookId, "skuId", skuId, "amountMinor", amountMinor));
        return itemId;
    }

    @Transactional
    public PriceBookView publish(Long bookId) {
        String tenantId = tenantContext.requireTenantId();
        PriceBookView before = requireDraft(tenantId, bookId);
        requireBookAccess(before);
        List<String> rows = mapper.listPriceCanonicalRows(tenantId, bookId);
        if (rows.isEmpty()) {
            throw new ServiceException("CAT-PRC-015: 空价格簿不得发布", 409);
        }
        String hash = sha256(String.join("\n", rows) + "\n");
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (mapper.publishPriceBook(tenantId, bookId, hash, now) != 1) {
            throw new ServiceException("CAT-PRC-016: 价格簿并发状态冲突", 409);
        }
        PriceBookView after = requireBook(tenantId, bookId);
        auditService.append("PRICE_BOOK_PUBLISHED", "PRICE_BOOK", bookId, before, after,
            Map.of("contentSha256", hash, "itemCount", rows.size()));
        outboxService.append(tenantId, "price-book.published.v1", "PRICE_BOOK", bookId, after.versionNo(),
            "{\"priceBookId\":" + bookId + ",\"versionNo\":" + after.versionNo() +
                ",\"contentSha256\":\"" + hash + "\"}");
        return after;
    }

    @Transactional
    public PriceBookView retire(Long bookId) {
        String tenantId = tenantContext.requireTenantId();
        PriceBookView before = requireBook(tenantId, bookId);
        requireBookAccess(before);
        if (!"PUBLISHED".equals(before.state()) || mapper.retirePriceBook(tenantId, bookId) != 1) {
            throw new ServiceException("CAT-PRC-020: 仅已发布价格版本可安全停用", 409);
        }
        PriceBookView after = requireBook(tenantId, bookId);
        auditService.append("PRICE_BOOK_RETIRED", "PRICE_BOOK", bookId, before, after,
            Map.of("contentSha256", before.contentSha256()));
        return after;
    }

    @Transactional(readOnly = true)
    @Override
    public ResolvedPrice resolve(Long skuId, Long unitId, Long storeId, Instant at) {
        String tenantId = tenantContext.requireTenantId();
        authorizationService.requireStoreAccess(storeId);
        Instant effectiveAt = at == null ? clock.instant() : at;
        List<Candidate> candidates = mapper.listPriceCandidates(tenantId, skuId, unitId, storeId,
                LocalDateTime.ofInstant(effectiveAt, ZoneOffset.UTC)).stream()
            .map(this::toCandidate).toList();
        return PriceResolution.resolve(candidates, storeId, effectiveAt);
    }

    private Candidate toCandidate(PriceCandidateView view) {
        return new Candidate(view.priceBookId(), view.priceItemId(), view.versionNo(), view.scopeType(), view.scopeStoreId(),
            view.amountMinor(), view.currency(), view.effectiveFrom(), view.effectiveTo(), view.published());
    }

    private void requireBookAccess(PriceBookView book) {
        if (book.storeId() != null) {
            authorizationService.requireStoreAccess(book.storeId());
        }
    }

    private String requireScope(String value, Long storeId) {
        String scope = value == null ? "" : value.trim().toUpperCase();
        if (!("TENANT_BASE".equals(scope) && storeId == null) && !("STORE".equals(scope) && storeId != null)) {
            throw new ServiceException("CAT-PRC-017: 价格范围形状无效", 400);
        }
        return scope;
    }

    private PriceBookView requireBook(String tenantId, Long bookId) {
        PriceBookView book = mapper.findPriceBook(tenantId, bookId);
        if (book == null) {
            throw new ServiceException("CAT-PRC-018: 价格簿不存在或不可见", 404);
        }
        return book;
    }

    private PriceBookView requireDraft(String tenantId, Long bookId) {
        PriceBookView book = requireBook(tenantId, bookId);
        if (!"DRAFT".equals(book.state())) {
            throw new ServiceException("CAT-PRC-019: 仅 DRAFT 价格簿可修改", 409);
        }
        return book;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
