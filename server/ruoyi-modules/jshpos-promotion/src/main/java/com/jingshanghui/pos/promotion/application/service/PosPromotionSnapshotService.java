package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.order.application.port.PromotionSnapshotIngestionPort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.AuditWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.OutboxWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.QuoteLineWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.QuoteWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.SnapshotLineWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.SnapshotWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredQuote;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredSnapshot;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredSnapshotLine;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接收离线 POS 已冻结的促销快照。服务端只验证身份、摘要和金额守恒，绝不按当前规则重算。
 */
@Service
@RequiredArgsConstructor
public class PosPromotionSnapshotService implements PromotionSnapshotIngestionPort {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SHA256 = "^[a-f0-9]{64}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final PromotionPersistencePort persistence;
    private final PromotionIdGenerator ids;

    @Override
    @Transactional
    public void ingest(SnapshotCommand command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        validate(command, principal);
        authorization.requireStoreAccess(command.storeId());
        if (persistence.findPackage(principal.tenantId(), command.storeId(), command.packageVersion()) == null) {
            throw new ServiceException("PRM-POS-005: 离线报价引用的规则包不存在", 409);
        }
        String canonicalHash = canonicalSnapshot(command).sha256();
        if (!canonicalHash.equals(command.snapshotSha256())) {
            throw new ServiceException("PRM-POS-006: POS成交优惠快照摘要不一致", 409);
        }
        StoredSnapshot existing = persistence.lockSnapshot(principal.tenantId(), command.snapshotId());
        if (existing != null) {
            requireExactReplay(command, existing, persistence.listSnapshotLines(principal.tenantId(),
                command.snapshotId()));
            return;
        }
        ensureQuote(principal, command);
        LocalDateTime occurredAt = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        persistence.insertSnapshot(new SnapshotWrite(principal.tenantId(), command.snapshotId(), command.orderId(),
            command.quoteId(), command.storeId(), command.terminalId(), command.businessDate(), "CNY",
            command.quoteFingerprint(), command.snapshotSha256(), command.grossAmountMinor(),
            command.discountAmountMinor(), command.payableAmountMinor(), principal.userId(),
            command.correlationId(), occurredAt));
        for (SnapshotLine line : ordered(command.lines())) {
            CanonicalJson.Result sources = CanonicalJson.from(new LinkedHashMap<>(line.sourceAllocations()));
            persistence.insertSnapshotLine(new SnapshotLineWrite(principal.tenantId(), ids.next(),
                command.snapshotId(), line.lineId(), line.lineNo(), line.skuId(), line.quantity(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor(),
                sources.json(), sources.sha256()));
        }
        Map<String, Object> summary = Map.of("orderId", command.orderId(), "quoteId", command.quoteId(),
            "sourceEventId", command.sourceEventId(), "snapshotSha256", command.snapshotSha256());
        CanonicalJson.Result evidence = CanonicalJson.from(summary);
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(), "POS_PROMOTION_SNAPSHOT_INGESTED",
            "PROMOTION_TRANSACTION", command.snapshotId(), principal.userId(), command.correlationId(), null,
            command.snapshotSha256(), evidence.json(), occurredAt));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(),
            "promotion.snapshot.ingested.v1", "PROMOTION_TRANSACTION", command.snapshotId(), 1,
            evidence.json(), evidence.sha256(), occurredAt));
    }

    private void ensureQuote(TrustedPrincipal principal, SnapshotCommand command) {
        StoredQuote quote = persistence.findQuote(principal.tenantId(), command.quoteId());
        if (quote != null) {
            if (!quote.storeId().equals(command.storeId()) || !quote.terminalId().equals(command.terminalId())
                || quote.packageVersion() != command.packageVersion()
                || quote.grossAmountMinor() != command.grossAmountMinor()
                || quote.discountAmountMinor() != command.discountAmountMinor()
                || quote.payableAmountMinor() != command.payableAmountMinor()
                || !quote.resultSha256().equals(command.quoteFingerprint())) {
                throw new ServiceException("PRM-POS-007: 报价身份已被不同内容占用", 409);
            }
            return;
        }
        CanonicalJson.Result request = canonicalQuote(command);
        LocalDateTime occurredAt = LocalDateTime.ofInstant(command.occurredAt(), ZoneOffset.UTC);
        persistence.insertQuote(new QuoteWrite(principal.tenantId(), command.quoteId(), command.storeId(),
            command.terminalId(), command.quoteId(), request.sha256(), command.engineVersion(),
            command.packageVersion(), occurredAt, command.grossAmountMinor(), command.discountAmountMinor(),
            command.payableAmountMinor(), "CNY", command.quoteFingerprint()));
        for (SnapshotLine line : ordered(command.lines())) {
            persistence.insertQuoteLine(new QuoteLineWrite(principal.tenantId(), ids.next(), command.quoteId(),
                line.lineId(), line.lineNo(), line.skuId(), line.quantity(), line.unitPriceMinor(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor()));
        }
    }

    private void validate(SnapshotCommand command, TrustedPrincipal principal) {
        if (command == null || !ulid(command.sourceEventId()) || !ulid(command.correlationId())
            || !ulid(command.quoteId()) || !ulid(command.snapshotId()) || !ulid(command.orderId())
            || command.storeId() == null || command.storeId() <= 0 || !ulid(command.terminalId())
            || command.businessDate() == null || command.packageVersion() <= 0 || command.occurredAt() == null
            || !"promotion-engine-1.0.0".equals(command.engineVersion())
            || !sha(command.quoteFingerprint()) || !sha(command.snapshotSha256())
            || command.lines().isEmpty() || command.lines().size() > 500) {
            throw new ServiceException("PRM-POS-001: POS促销快照上下文无效", 400);
        }
        long gross = 0; long discount = 0; long payable = 0;
        java.util.Set<String> idsSeen = new java.util.HashSet<>();
        java.util.Set<Integer> numbers = new java.util.HashSet<>();
        for (SnapshotLine line : command.lines()) {
            if (!ulid(line.lineId()) || line.lineNo() < 1 || line.lineNo() > 500 || line.skuId() == null
                || line.skuId() <= 0 || line.quantity() == null || line.quantity().signum() <= 0
                || line.quantity().scale() > 6 || line.unitPriceMinor() < 0 || line.grossAmountMinor() < 0
                || line.discountAmountMinor() < 0 || line.payableAmountMinor() < 0
                || line.grossAmountMinor() != line.discountAmountMinor() + line.payableAmountMinor()
                || !idsSeen.add(line.lineId()) || !numbers.add(line.lineNo())
                || line.sourceAllocations().values().stream().anyMatch(value -> value == null || value <= 0)
                || line.sourceAllocations().values().stream().mapToLong(Long::longValue).sum()
                    != line.discountAmountMinor()) {
                throw new ServiceException("PRM-POS-002: POS促销快照行无效", 400);
            }
            gross = Math.addExact(gross, line.grossAmountMinor());
            discount = Math.addExact(discount, line.discountAmountMinor());
            payable = Math.addExact(payable, line.payableAmountMinor());
        }
        if (gross != command.grossAmountMinor() || discount != command.discountAmountMinor()
            || payable != command.payableAmountMinor() || gross != discount + payable
            || principal.userId() == null) {
            throw new ServiceException("PRM-POS-003: POS促销快照金额不守恒", 409);
        }
    }

    private void requireExactReplay(SnapshotCommand command, StoredSnapshot existing,
                                    List<StoredSnapshotLine> lines) {
        if (!existing.orderId().equals(command.orderId()) || !existing.quoteId().equals(command.quoteId())
            || !existing.storeId().equals(command.storeId()) || !existing.terminalId().equals(command.terminalId())
            || !existing.businessDate().equals(command.businessDate())
            || !existing.quoteFingerprint().equals(command.quoteFingerprint())
            || !existing.snapshotSha256().equals(command.snapshotSha256()) || lines.size() != command.lines().size()) {
            throw new ServiceException("PRM-POS-004: 同一快照标识对应不同内容", 409);
        }
    }

    private CanonicalJson.Result canonicalQuote(SnapshotCommand command) {
        return CanonicalJson.from(Map.of("quoteId", command.quoteId(), "storeId", command.storeId(),
            "terminalId", command.terminalId(), "packageVersion", command.packageVersion(),
            "businessDate", command.businessDate().toString(), "lines", canonicalLines(command.lines())));
    }

    private CanonicalJson.Result canonicalSnapshot(SnapshotCommand command) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("snapshotId", command.snapshotId()); content.put("orderId", command.orderId());
        content.put("quoteId", command.quoteId()); content.put("storeId", command.storeId());
        content.put("terminalId", command.terminalId()); content.put("currency", "CNY");
        content.put("quoteFingerprint", command.quoteFingerprint());
        content.put("grossAmountMinor", command.grossAmountMinor());
        content.put("discountAmountMinor", command.discountAmountMinor());
        content.put("payableAmountMinor", command.payableAmountMinor());
        content.put("lines", canonicalLines(command.lines()));
        return CanonicalJson.from(content);
    }

    private List<Map<String, Object>> canonicalLines(List<SnapshotLine> lines) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SnapshotLine line : ordered(lines)) {
            CanonicalJson.Result sources = CanonicalJson.from(new LinkedHashMap<>(line.sourceAllocations()));
            result.add(Map.of("lineId", line.lineId(), "lineNo", line.lineNo(), "skuId", line.skuId(),
                "quantity", line.quantity().toPlainString(), "grossAmountMinor", line.grossAmountMinor(),
                "discountAmountMinor", line.discountAmountMinor(), "payableAmountMinor", line.payableAmountMinor(),
                "sourceAllocationsSha256", sources.sha256()));
        }
        return result;
    }

    private List<SnapshotLine> ordered(List<SnapshotLine> lines) {
        return lines.stream().sorted(Comparator.comparingInt(SnapshotLine::lineNo)
            .thenComparing(SnapshotLine::skuId).thenComparing(SnapshotLine::lineId)).toList();
    }

    private boolean ulid(String value) { return value != null && value.matches(ULID); }
    private boolean sha(String value) { return value != null && value.matches(SHA256); }
}
