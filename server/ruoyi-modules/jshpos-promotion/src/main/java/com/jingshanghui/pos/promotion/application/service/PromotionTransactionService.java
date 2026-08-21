package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.AllocateRefund;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.FreezeSnapshot;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.*;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine;
import com.jingshanghui.pos.promotion.domain.TransactionAllocationEngine.*;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** PRM-003 成交优惠快照、金额守恒分摊与原快照退款恢复应用服务。 */
@Service
@RequiredArgsConstructor
public class PromotionTransactionService {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SHA256 = "^[a-f0-9]{64}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final StoreService stores;
    private final PromotionPersistencePort persistence;
    private final TransactionAllocationEngine engine;
    private final PromotionIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 锁定报价聚合后一次性写入快照头、逐行分摊、幂等结果、审计与 Outbox。 */
    @Transactional
    public SnapshotView freeze(FreezeSnapshot command) {
        requireFreeze(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String requestHash = canonicalFreeze(command).sha256();
        SnapshotView replay = replay(principal.tenantId(), "PROMOTION_SNAPSHOT_FREEZE", command.commandId(),
            requestHash, SnapshotView.class);
        if (replay != null) return replay;
        StoredQuote quote = persistence.lockQuote(principal.tenantId(), command.quoteId());
        if (quote == null) throw new ServiceException("PRM-SNAPSHOT-010: 报价不存在或不可见", 404);
        authorization.requireStoreAccess(quote.storeId());
        if (persistence.findSnapshotByQuote(principal.tenantId(), quote.quoteId()) != null
            || persistence.findSnapshotByOrder(principal.tenantId(), command.orderId()) != null) {
            throw new ServiceException("PRM-SNAPSHOT-011: 报价或订单已经冻结成交优惠快照", 409);
        }
        if (persistence.findPendingManualEvent(principal.tenantId(), quote.quoteId()) != null) {
            throw new ServiceException("PRM-SNAPSHOT-012: 待复核人工优惠不得进入成交冻结", 409);
        }
        ResolvedQuote resolved = resolveQuote(principal.tenantId(), quote);
        if (!resolved.fingerprint().equals(command.quoteFingerprint())) {
            throw new ServiceException("PRM-SNAPSHOT-013: 最终报价指纹已变化", 409);
        }
        List<StoredQuoteLine> sourceLines = persistence.listQuoteLines(principal.tenantId(), quote.quoteId());
        Map<String, QuoteLine> resultLines = new LinkedHashMap<>();
        resolved.result().lines().forEach(line -> resultLines.put(line.lineId(), line));
        List<SnapshotLine> inputs = sourceLines.stream().map(line -> {
            QuoteLine result = resultLines.get(line.sourceLineId());
            if (result == null) throw new ServiceException("PRM-SNAPSHOT-014: 最终报价缺少原始行", 500);
            return new SnapshotLine(line.sourceLineId(), line.lineNo(), line.skuId(), line.quantity(),
                result.grossAmountMinor(), result.discountAmountMinor(), result.payableAmountMinor());
        }).toList();
        Snapshot snapshot = engine.freeze(inputs);
        if (snapshot.grossAmountMinor() != resolved.result().grossAmountMinor()
            || snapshot.discountAmountMinor() != resolved.result().discountAmountMinor()
            || snapshot.payableAmountMinor() != resolved.result().payableAmountMinor()) {
            throw new ServiceException("PRM-SNAPSHOT-015: 最终报价头行金额不一致", 500);
        }
        List<SnapshotLineView> allocationViews = allocationViews(snapshot, resolved.result());
        String snapshotHash = canonicalSnapshot(command.snapshotId(), command.orderId(), quote,
            resolved.fingerprint(), snapshot, allocationViews).sha256();
        SnapshotView view = new SnapshotView(command.snapshotId(), command.orderId(), quote.quoteId(),
            quote.storeId(), quote.currency(), resolved.fingerprint(), snapshotHash, snapshot.grossAmountMinor(),
            snapshot.discountAmountMinor(), snapshot.payableAmountMinor(), allocationViews);
        LocalDateTime now = LocalDateTime.now(clock);
        persistence.insertSnapshot(new SnapshotWrite(principal.tenantId(), command.snapshotId(), command.orderId(),
            quote.quoteId(), quote.storeId(), quote.terminalId(),
            stores.businessDate(quote.storeId(), instant(quote.businessTime())).businessDate(), quote.currency(),
            resolved.fingerprint(), snapshotHash, snapshot.grossAmountMinor(), snapshot.discountAmountMinor(),
            snapshot.payableAmountMinor(), principal.userId(), command.correlationId(), now));
        for (SnapshotLineView line : allocationViews) {
            persistence.insertSnapshotLine(new SnapshotLineWrite(principal.tenantId(), ids.next(),
                command.snapshotId(), line.lineId(), line.lineNo(), line.skuId(), line.quantity(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor(),
                line.sourceAllocationsJson(), line.sourceAllocationsSha256()));
        }
        persistCommand(principal.tenantId(), "PROMOTION_SNAPSHOT_FREEZE", command.commandId(), requestHash,
            "PROMOTION_TRANSACTION_SNAPSHOT", command.snapshotId(), view);
        appendAuditAndOutbox(principal, "PROMOTION_SNAPSHOT_FROZEN", "promotion.snapshot.frozen.v1",
            command.snapshotId(), command.correlationId(), resolved.fingerprint(), snapshotHash,
            Map.of("orderId", command.orderId(), "quoteId", quote.quoteId(),
                "discountAmountMinor", snapshot.discountAmountMinor()), now);
        return view;
    }

    /** 锁定成交快照后按累计原事实计算并只追加本次退款恢复流水。 */
    @Transactional
    public RefundAllocationView allocateRefund(AllocateRefund command) {
        requireRefund(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String requestHash = canonicalRefund(command).sha256();
        RefundAllocationView replay = replay(principal.tenantId(), "PROMOTION_REFUND_ALLOCATE", command.commandId(),
            requestHash, RefundAllocationView.class);
        if (replay != null) return replay;
        StoredSnapshot stored = persistence.lockSnapshot(principal.tenantId(), command.snapshotId());
        if (stored == null) throw new ServiceException("PRM-REFUND-011: 成交优惠快照不存在或不可见", 404);
        authorization.requireStoreAccess(stored.storeId());
        ExistingRefund existing = persistence.findRefund(principal.tenantId(), command.refundId());
        if (existing != null) throw new ServiceException("PRM-REFUND-012: 退款标识已经用于另一命令", 409);
        List<StoredSnapshotLine> storedLines = persistence.listSnapshotLines(principal.tenantId(), stored.snapshotId());
        Snapshot snapshot = engine.freeze(storedLines.stream().map(line -> new SnapshotLine(line.lineId(),
            line.lineNo(), line.skuId(), line.quantity(), line.grossAmountMinor(), line.discountAmountMinor(),
            line.payableAmountMinor())).toList());
        if (snapshot.grossAmountMinor() != stored.grossAmountMinor()
            || snapshot.discountAmountMinor() != stored.discountAmountMinor()
            || snapshot.payableAmountMinor() != stored.payableAmountMinor()
            || !canonicalStoredSnapshot(stored, storedLines).sha256().equals(stored.snapshotSha256())) {
            throw new ServiceException("PRM-REFUND-013: 成交优惠快照摘要或金额已损坏", 500);
        }
        List<PriorRefund> history = persistence.listRefundHistory(principal.tenantId(), stored.snapshotId()).stream()
            .map(row -> new PriorRefund(row.lineId(), row.quantity(), row.grossAmountMinor(),
                row.discountAmountMinor(), row.payableAmountMinor())).toList();
        RefundResult result = engine.refund(snapshot, history, command.lines().stream().map(line ->
            new RefundRequestLine(line.lineId(), line.quantity())).toList());
        List<RefundLineView> lines = result.lines().stream().map(line -> new RefundLineView(line.lineId(),
            line.quantity(), line.grossAmountMinor(), line.recoveredDiscountMinor(), line.refundableAmountMinor(),
            line.cumulativeQuantity(), line.cumulativeGrossAmountMinor(), line.cumulativeDiscountAmountMinor(),
            line.cumulativePayableAmountMinor())).toList();
        RefundAllocationView view = new RefundAllocationView(command.refundId(), command.snapshotId(),
            result.grossAmountMinor(), result.recoveredDiscountMinor(), result.refundableAmountMinor(), lines);
        LocalDateTime now = LocalDateTime.now(clock);
        for (RefundLineView line : lines) {
            persistence.insertRefundAllocation(new RefundAllocationWrite(principal.tenantId(), ids.next(),
                command.snapshotId(), command.refundId(), line.lineId(), command.commandId(), requestHash,
                line.quantity(), line.grossAmountMinor(), line.recoveredDiscountMinor(), line.refundableAmountMinor(),
                line.cumulativeQuantity(), line.cumulativeGrossAmountMinor(), line.cumulativeDiscountAmountMinor(),
                line.cumulativePayableAmountMinor(), principal.userId(), command.correlationId(), now));
        }
        persistCommand(principal.tenantId(), "PROMOTION_REFUND_ALLOCATE", command.commandId(), requestHash,
            "PROMOTION_REFUND_ALLOCATION", command.refundId(), view);
        appendAuditAndOutbox(principal, "PROMOTION_REFUND_ALLOCATED", "promotion.refund.allocated.v1",
            command.refundId(), command.correlationId(), stored.snapshotSha256(), canonicalView(view).sha256(),
            Map.of("snapshotId", stored.snapshotId(), "refundableAmountMinor", result.refundableAmountMinor(),
                "recoveredDiscountMinor", result.recoveredDiscountMinor()), now);
        return view;
    }

    /** 使用与正式退款完全相同的原快照算法做只读预检，不创建账本或幂等事实。 */
    @Transactional(readOnly = true)
    public RefundPreviewView previewRefund(
        String snapshotId,
        List<com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine> lines
    ) {
        requireUlid(snapshotId);
        requireRefundLines(lines);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        StoredSnapshot stored = persistence.findSnapshot(principal.tenantId(), snapshotId);
        if (stored == null) throw new ServiceException("PRM-REFUND-011: 成交优惠快照不存在或不可见", 404);
        authorization.requireStoreAccess(stored.storeId());
        List<StoredSnapshotLine> storedLines = persistence.listSnapshotLines(principal.tenantId(), snapshotId);
        Snapshot snapshot = engine.freeze(storedLines.stream().map(line -> new SnapshotLine(line.lineId(),
            line.lineNo(), line.skuId(), line.quantity(), line.grossAmountMinor(), line.discountAmountMinor(),
            line.payableAmountMinor())).toList());
        if (snapshot.grossAmountMinor() != stored.grossAmountMinor()
            || snapshot.discountAmountMinor() != stored.discountAmountMinor()
            || snapshot.payableAmountMinor() != stored.payableAmountMinor()
            || !canonicalStoredSnapshot(stored, storedLines).sha256().equals(stored.snapshotSha256())) {
            throw new ServiceException("PRM-REFUND-013: 成交优惠快照摘要或金额已损坏", 500);
        }
        List<PriorRefund> history = persistence.listRefundHistory(principal.tenantId(), snapshotId).stream()
            .map(row -> new PriorRefund(row.lineId(), row.quantity(), row.grossAmountMinor(),
                row.discountAmountMinor(), row.payableAmountMinor())).toList();
        RefundResult result = engine.refund(snapshot, history, lines.stream()
            .map(line -> new RefundRequestLine(line.lineId(), line.quantity())).toList());
        return new RefundPreviewView(snapshotId, result.grossAmountMinor(), result.recoveredDiscountMinor(),
            result.refundableAmountMinor(), result.lines().stream().map(line -> new RefundLineView(line.lineId(),
                line.quantity(), line.grossAmountMinor(), line.recoveredDiscountMinor(),
                line.refundableAmountMinor(), line.cumulativeQuantity(), line.cumulativeGrossAmountMinor(),
                line.cumulativeDiscountAmountMinor(), line.cumulativePayableAmountMinor())).toList());
    }

    private ResolvedQuote resolveQuote(String tenantId, StoredQuote quote) {
        List<ManualEvent> applied = persistence.listAppliedManualEvents(tenantId, quote.quoteId());
        if (!applied.isEmpty()) {
            ManualEvent latest = applied.get(applied.size() - 1);
            QuoteResult result = readQuoteResult(latest.resultJson(), latest.resultSha256());
            if (!latest.previewFingerprint().equals(latest.resultSha256())) {
                throw new ServiceException("PRM-SNAPSHOT-016: 人工优惠结果指纹不一致", 500);
            }
            return new ResolvedQuote(result, latest.previewFingerprint());
        }
        List<StoredQuoteLine> storedLines = persistence.listQuoteLines(tenantId, quote.quoteId());
        List<QuoteLine> lines = storedLines.stream().map(line -> new QuoteLine(line.sourceLineId(),
            line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor())).toList();
        List<StoredAdjustment> storedAdjustments = persistence.listQuoteAdjustments(tenantId, quote.quoteId());
        Map<String, Map<String, Long>> grouped = new LinkedHashMap<>();
        Set<String> appliedIds = new LinkedHashSet<>(); List<Explanation> rejected = new ArrayList<>();
        for (StoredAdjustment value : storedAdjustments) {
            if (value.appliedFlag()) {
                appliedIds.add(value.sourceId());
                grouped.computeIfAbsent(value.sourceId(), ignored -> new LinkedHashMap<>())
                    .put(value.sourceLineId(), value.amountMinor());
            } else rejected.add(new Explanation(value.sourceId(), value.explanationCode()));
        }
        List<AppliedAdjustment> adjustments = grouped.entrySet().stream().map(entry -> new AppliedAdjustment(
            entry.getKey(), entry.getValue().values().stream().mapToLong(Long::longValue).sum(), entry.getValue())).toList();
        List<Explanation> explanations = new ArrayList<>();
        appliedIds.forEach(id -> explanations.add(new Explanation(id, "APPLIED"))); explanations.addAll(rejected);
        QuoteResult result = new QuoteResult(quote.grossAmountMinor(), quote.discountAmountMinor(),
            quote.payableAmountMinor(), lines, List.copyOf(appliedIds), explanations, adjustments);
        if (!canonicalBaseResult(result).sha256().equals(quote.resultSha256())) {
            throw new ServiceException("PRM-SNAPSHOT-017: 原始报价事实摘要不一致", 500);
        }
        return new ResolvedQuote(result, quote.resultSha256());
    }

    private List<SnapshotLineView> allocationViews(Snapshot snapshot, QuoteResult result) {
        List<SnapshotLineView> views = new ArrayList<>();
        for (SnapshotLine line : snapshot.lines()) {
            Map<String, Long> sources = new TreeMap<>();
            for (AppliedAdjustment adjustment : result.adjustments()) {
                long amount = adjustment.lineAllocations().getOrDefault(line.lineId(), 0L);
                if (amount > 0) sources.put(adjustment.sourceId(), amount);
            }
            Map<String, Object> canonicalSources = new LinkedHashMap<>();
            sources.forEach(canonicalSources::put);
            CanonicalJson.Result canonical = CanonicalJson.from(canonicalSources);
            long allocated = sources.values().stream().mapToLong(Long::longValue).sum();
            if (allocated != line.discountAmountMinor()) {
                throw new ServiceException("PRM-SNAPSHOT-018: 行优惠来源分摊与成交优惠不一致", 500);
            }
            views.add(new SnapshotLineView(line.lineId(), line.lineNo(), line.skuId(), line.quantity(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor(),
                canonical.json(), canonical.sha256()));
        }
        return views;
    }

    private CanonicalJson.Result canonicalSnapshot(String snapshotId, String orderId, StoredQuote quote,
                                                    String fingerprint, Snapshot snapshot,
                                                    List<SnapshotLineView> lines) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("snapshotId", snapshotId); content.put("orderId", orderId); content.put("quoteId", quote.quoteId());
        content.put("storeId", quote.storeId()); content.put("terminalId", quote.terminalId());
        content.put("currency", quote.currency()); content.put("quoteFingerprint", fingerprint);
        content.put("grossAmountMinor", snapshot.grossAmountMinor());
        content.put("discountAmountMinor", snapshot.discountAmountMinor());
        content.put("payableAmountMinor", snapshot.payableAmountMinor()); content.put("lines", canonicalLines(lines));
        return CanonicalJson.from(content);
    }

    private CanonicalJson.Result canonicalStoredSnapshot(StoredSnapshot stored, List<StoredSnapshotLine> lines) {
        for (StoredSnapshotLine line : lines) {
            try {
                Map<String, Object> sources = objectMapper.readValue(line.sourceAllocationsJson(), new TypeReference<>() { });
                if (!CanonicalJson.from(sources).sha256().equals(line.sourceAllocationsSha256())) {
                    throw new ServiceException("PRM-REFUND-013: 成交优惠来源分摊摘要已损坏", 500);
                }
            } catch (JsonProcessingException exception) {
                throw new ServiceException("PRM-REFUND-013: 成交优惠来源分摊JSON已损坏", 500);
            }
        }
        List<SnapshotLineView> views = lines.stream().map(line -> new SnapshotLineView(line.lineId(), line.lineNo(),
            line.skuId(), line.quantity(), line.grossAmountMinor(), line.discountAmountMinor(),
            line.payableAmountMinor(), line.sourceAllocationsJson(), line.sourceAllocationsSha256())).toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("snapshotId", stored.snapshotId()); content.put("orderId", stored.orderId());
        content.put("quoteId", stored.quoteId()); content.put("storeId", stored.storeId());
        content.put("terminalId", stored.terminalId()); content.put("currency", stored.currency());
        content.put("quoteFingerprint", stored.quoteFingerprint());
        content.put("grossAmountMinor", stored.grossAmountMinor());
        content.put("discountAmountMinor", stored.discountAmountMinor());
        content.put("payableAmountMinor", stored.payableAmountMinor()); content.put("lines", canonicalLines(views));
        return CanonicalJson.from(content);
    }

    private List<Map<String, Object>> canonicalLines(List<SnapshotLineView> lines) {
        return lines.stream().sorted(Comparator.comparingInt(SnapshotLineView::lineNo)
            .thenComparing(SnapshotLineView::skuId).thenComparing(SnapshotLineView::lineId)).map(line ->
            Map.<String, Object>of("lineId", line.lineId(), "lineNo", line.lineNo(), "skuId", line.skuId(),
                "quantity", line.quantity().toPlainString(), "grossAmountMinor", line.grossAmountMinor(),
                "discountAmountMinor", line.discountAmountMinor(), "payableAmountMinor", line.payableAmountMinor(),
                "sourceAllocationsSha256", line.sourceAllocationsSha256())).toList();
    }

    private CanonicalJson.Result canonicalFreeze(FreezeSnapshot value) {
        return CanonicalJson.from(Map.of("snapshotId", value.snapshotId(), "orderId", value.orderId(),
            "quoteId", value.quoteId(), "quoteFingerprint", value.quoteFingerprint()));
    }

    private CanonicalJson.Result canonicalRefund(AllocateRefund value) {
        List<Map<String, String>> lines = value.lines().stream().sorted(Comparator.comparing(
            com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine::lineId)).map(line ->
            Map.of("lineId", line.lineId(), "quantity", line.quantity().stripTrailingZeros().toPlainString())).toList();
        return CanonicalJson.from(Map.of("snapshotId", value.snapshotId(), "refundId", value.refundId(), "lines", lines));
    }

    private CanonicalJson.Result canonicalBaseResult(QuoteResult value) {
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", value.grossAmountMinor(), "discountAmountMinor", value.discountAmountMinor(),
            "payableAmountMinor", value.payableAmountMinor(), "lineDiscounts", value.lineDiscounts(),
            "appliedRuleIds", value.appliedRuleIds()));
    }

    private CanonicalJson.Result canonicalManualResult(QuoteResult value) {
        List<Map<String, Object>> adjustments = value.adjustments().stream().map(item -> Map.<String, Object>of(
            "sourceId", item.sourceId(), "amountMinor", item.amountMinor(),
            "lineAllocations", new TreeMap<>(item.lineAllocations()))).toList();
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", value.grossAmountMinor(), "discountAmountMinor", value.discountAmountMinor(),
            "payableAmountMinor", value.payableAmountMinor(), "lineDiscounts", value.lineDiscounts(),
            "appliedRuleIds", value.appliedRuleIds(), "adjustments", adjustments,
            "explanations", value.explanations().stream().map(item -> item.sourceId() + ":" + item.code()).toList()));
    }

    private QuoteResult readQuoteResult(String json, String expectedHash) {
        try {
            QuoteResult result = objectMapper.readValue(json, QuoteResult.class);
            if (!canonicalManualResult(result).sha256().equals(expectedHash)) {
                throw new ServiceException("PRM-SNAPSHOT-019: 人工优惠结果摘要不一致", 500);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-SNAPSHOT-019: 人工优惠结果损坏", 500);
        }
    }

    private <T> T replay(String tenantId, String type, String key, String requestHash, Class<T> resultType) {
        StoredCommand stored = persistence.findCommand(tenantId, type, key);
        if (stored == null) return null;
        if (!stored.requestSha256().equals(requestHash)) {
            throw new ServiceException("PRM-IDEMP-003: 同一命令幂等键对应不同内容", 409);
        }
        try {
            T value = objectMapper.readValue(stored.resultJson(), resultType);
            if (!canonicalView(value).sha256().equals(stored.resultSha256())) {
                throw new ServiceException("PRM-IDEMP-004: 命令结果摘要不一致", 500);
            }
            return value;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-IDEMP-004: 命令结果损坏", 500);
        }
    }

    private void persistCommand(String tenantId, String type, String key, String requestHash,
                                String aggregateType, String aggregateId, Object value) {
        String json = write(value);
        persistence.insertCommand(new CommandWrite(tenantId, ids.next(), type, key, requestHash, aggregateType,
            aggregateId, canonicalView(value).sha256(), json));
    }

    private CanonicalJson.Result canonicalView(Object value) {
        Map<String, Object> content = objectMapper.convertValue(value, new TypeReference<>() { });
        return CanonicalJson.from(content);
    }

    private void appendAuditAndOutbox(TrustedPrincipal principal, String action, String eventType,
                                      String aggregateId, String correlationId, String before, String after,
                                      Map<String, Object> summary, LocalDateTime now) {
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(), action,
            "PROMOTION_TRANSACTION", aggregateId, principal.userId(), correlationId, before, after,
            write(summary), now));
        CanonicalJson.Result payload = CanonicalJson.from(summary);
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(), eventType,
            "PROMOTION_TRANSACTION", aggregateId, 1, payload.json(), payload.sha256(), now));
    }

    private void requireFreeze(FreezeSnapshot value) {
        if (value == null) throw new ServiceException("PRM-SNAPSHOT-020: 成交冻结请求无效", 400);
        requireUlid(value.commandId()); requireUlid(value.snapshotId()); requireUlid(value.orderId());
        requireUlid(value.quoteId()); requireUlid(value.correlationId()); requireSha(value.quoteFingerprint());
    }

    private void requireRefund(AllocateRefund value) {
        if (value == null || value.lines() == null || value.lines().isEmpty() || value.lines().size() > 500) {
            throw new ServiceException("PRM-REFUND-014: 退款分摊请求无效", 400);
        }
        requireUlid(value.commandId()); requireUlid(value.snapshotId()); requireUlid(value.refundId());
        requireUlid(value.correlationId());
        requireRefundLines(value.lines());
    }

    private void requireRefundLines(
        List<com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine> lines
    ) {
        if (lines == null || lines.isEmpty() || lines.size() > 500) {
            throw new ServiceException("PRM-REFUND-014: 退款分摊请求无效", 400);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine line : lines) {
            if (line == null) throw new ServiceException("PRM-REFUND-014: 退款分摊请求无效", 400);
            requireUlid(line.lineId());
            if (!unique.add(line.lineId()) || line.quantity() == null || line.quantity().signum() <= 0
                || line.quantity().scale() > 6 || line.quantity().precision() > 19) {
                throw new ServiceException("PRM-REFUND-014: 退款分摊请求无效", 400);
            }
        }
    }

    private void requireUlid(String value) {
        if (value == null || !value.matches(ULID)) throw new ServiceException("PRM-INPUT-001: ULID无效", 400);
    }
    private void requireSha(String value) {
        if (value == null || !value.matches(SHA256)) throw new ServiceException("PRM-INPUT-004: SHA-256无效", 400);
    }
    private String write(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new ServiceException("PRM-IDEMP-005: 结果无法序列化", 500); }
    }
    private Instant instant(LocalDateTime value) { return value.toInstant(ZoneOffset.UTC); }
    private record ResolvedQuote(QuoteResult result, String fingerprint) { }
}
