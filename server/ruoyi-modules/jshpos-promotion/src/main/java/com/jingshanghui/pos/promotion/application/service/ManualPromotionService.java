package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.ManualApprove;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.ManualAuthorize;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.ManualAuthorizationView;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.*;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PRM-002 受权人工优惠、独立复核、幂等重放和只追加审计应用服务。 */
@Service
@RequiredArgsConstructor
public class ManualPromotionService {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private static final String SHA256 = "^[a-f0-9]{64}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final StoreService stores;
    private final PromotionPersistencePort persistence;
    private final ManualAdjustmentEngine engine;
    private final ManualPolicyCodec policyCodec;
    private final PromotionIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** 阈值内直接应用，超阈值仅创建待复核事件，不把预览当作成交事实。 */
    @Transactional
    public ManualAuthorizationView authorize(ManualAuthorize command) {
        requireAuthorize(command);
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        String requestHash = canonicalAuthorize(command).sha256();
        ManualAuthorizationView replay = replay(principal.tenantId(), "MANUAL_AUTHORIZE", command.commandId(), requestHash);
        if (replay != null) return replay;
        StoredQuote quote = requireLockedQuote(principal.tenantId(), command.quoteId());
        authorization.requireStoreAccess(quote.storeId());
        requireUnfrozen(principal.tenantId(), quote.quoteId());
        if (persistence.findPendingManualEvent(principal.tenantId(), quote.quoteId()) != null) {
            throw new ServiceException("PRM-AUTH-004: 报价存在待复核人工优惠", 409);
        }
        List<ManualEvent> applied = persistence.listAppliedManualEvents(principal.tenantId(), quote.quoteId());
        requireStage(command, applied);
        Current current = current(principal.tenantId(), quote, applied);
        if (!current.fingerprint().equals(command.expectedQuoteFingerprint())) {
            throw new ServiceException("PRM-AUTH-005: 报价指纹已变化", 409);
        }
        Policy policy = policyCodec.decode(persistence.findManualPolicy(principal.tenantId(), quote.storeId()));
        List<StoredQuoteLine> storedLines = persistence.listQuoteLines(principal.tenantId(), quote.quoteId());
        List<LineContext> contexts = storedLines.stream().map(line -> new LineContext(line.sourceLineId(),
            line.lineNo(), line.skuId(), line.quantity())).toList();
        Preview preview = engine.preview(current.result(), contexts, new Command(command.authorizationId(),
            command.actionType(), command.lineId(), command.amountOrRate(), command.paymentMethod()), policy);
        String previewFingerprint = canonicalManualResult(preview.result()).sha256();
        String state = preview.requiresApproval() ? "PENDING_APPROVAL" : "APPLIED";
        ManualAuthorizationView result = new ManualAuthorizationView(command.authorizationId(), state, quote.quoteId(),
            command.actionType().name(), principal.userId(), null, policy.policyVersionId(), current.fingerprint(),
            previewFingerprint, preview.incrementalDiscountMinor(), preview.result());
        insertEvent(command, quote, principal, policy, requestHash, state, 1, null, result);
        persistCommand(principal.tenantId(), "MANUAL_AUTHORIZE", command.commandId(), requestHash, result);
        appendAuditAndOutbox(principal, result, command.correlationId(), 1);
        return result;
    }

    /** 由不同的当前认证主体批准超阈值请求；审批人标识不采信请求体。 */
    @Transactional
    public ManualAuthorizationView approve(ManualApprove command) {
        requireApprove(command);
        TrustedPrincipal approver = tenantContext.requirePrincipal();
        String requestHash = canonicalApprove(command).sha256();
        ManualAuthorizationView replay = replay(approver.tenantId(), "MANUAL_APPROVE", command.commandId(), requestHash);
        if (replay != null) return replay;
        ManualEvent candidate = persistence.findLatestManualEvent(approver.tenantId(), command.authorizationId());
        if (candidate == null || !"PENDING_APPROVAL".equals(candidate.state())) {
            throw new ServiceException("PRM-AUTH-006: 待复核人工优惠不存在或状态已变化", 409);
        }
        authorization.requireStoreAccess(candidate.storeId());
        StoredQuote quote = requireLockedQuote(approver.tenantId(), candidate.quoteId());
        requireUnfrozen(approver.tenantId(), quote.quoteId());
        ManualEvent pending = persistence.findLatestManualEvent(approver.tenantId(), command.authorizationId());
        if (pending == null || !"PENDING_APPROVAL".equals(pending.state())) {
            throw new ServiceException("PRM-AUTH-006: 待复核人工优惠不存在或状态已变化", 409);
        }
        if (approver.userId().equals(pending.operatorUserId())) {
            throw new ServiceException("PRM-AUTH-007: 操作人与复核人必须分离", 403);
        }
        if (!pending.previewFingerprint().equals(command.expectedPreviewFingerprint())) {
            throw new ServiceException("PRM-AUTH-008: 预检指纹不一致", 409);
        }
        List<ManualEvent> applied = persistence.listAppliedManualEvents(approver.tenantId(), quote.quoteId());
        Current current = current(approver.tenantId(), quote, applied);
        if (!current.fingerprint().equals(pending.beforeFingerprint())) {
            throw new ServiceException("PRM-AUTH-005: 报价指纹已变化", 409);
        }
        QuoteResult preview = readQuoteResult(pending.resultJson(), pending.resultSha256());
        ManualAuthorizationView result = new ManualAuthorizationView(pending.authorizationId(), "APPLIED",
            pending.quoteId(), pending.actionType(), pending.operatorUserId(), approver.userId(),
            pending.policyVersionId(), pending.beforeFingerprint(), pending.previewFingerprint(),
            pending.incrementalDiscountMinor(), preview);
        insertApprovalEvent(command, pending, approver, requestHash, result);
        persistCommand(approver.tenantId(), "MANUAL_APPROVE", command.commandId(), requestHash, result);
        appendAuditAndOutbox(approver, result, command.correlationId(), pending.eventSequence() + 1L);
        return result;
    }

    private Current current(String tenantId, StoredQuote quote, List<ManualEvent> applied) {
        if (applied.isEmpty()) return new Current(rebuildBaseQuote(tenantId, quote), quote.resultSha256());
        ManualEvent latest = applied.get(applied.size() - 1);
        return new Current(readQuoteResult(latest.resultJson(), latest.resultSha256()), latest.previewFingerprint());
    }

    private QuoteResult rebuildBaseQuote(String tenantId, StoredQuote quote) {
        List<StoredQuoteLine> storedLines = persistence.listQuoteLines(tenantId, quote.quoteId());
        List<QuoteLine> lines = storedLines.stream().map(line -> new QuoteLine(line.sourceLineId(),
            line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor())).toList();
        List<StoredAdjustment> storedAdjustments = persistence.listQuoteAdjustments(tenantId, quote.quoteId());
        Map<String, Map<String, Long>> grouped = new LinkedHashMap<>();
        List<Explanation> explanations = new ArrayList<>();
        Set<String> appliedIds = new LinkedHashSet<>();
        for (StoredAdjustment value : storedAdjustments) {
            if (value.appliedFlag()) {
                appliedIds.add(value.sourceId());
                grouped.computeIfAbsent(value.sourceId(), ignored -> new LinkedHashMap<>())
                    .put(value.sourceLineId(), value.amountMinor());
            } else {
                explanations.add(new Explanation(value.sourceId(), value.explanationCode()));
            }
        }
        List<AppliedAdjustment> adjustments = grouped.entrySet().stream().map(entry -> new AppliedAdjustment(
            entry.getKey(), entry.getValue().values().stream().mapToLong(Long::longValue).sum(), entry.getValue())).toList();
        List<Explanation> ordered = new ArrayList<>();
        appliedIds.forEach(value -> ordered.add(new Explanation(value, "APPLIED")));
        ordered.addAll(explanations);
        QuoteResult result = new QuoteResult(quote.grossAmountMinor(), quote.discountAmountMinor(),
            quote.payableAmountMinor(), lines, List.copyOf(appliedIds), ordered, adjustments);
        if (!canonicalBaseResult(result).sha256().equals(quote.resultSha256())) {
            throw new ServiceException("PRM-AUTH-009: 原始报价事实摘要不一致", 500);
        }
        return result;
    }

    private void requireStage(ManualAuthorize command, List<ManualEvent> applied) {
        int requested = stage(command.actionType().name());
        if (!applied.isEmpty() && requested < stage(applied.get(applied.size() - 1).actionType())) {
            throw new ServiceException("PRM-AUTH-014: 人工优惠动作违反固定计算顺序", 409);
        }
        if (command.actionType() != ActionType.LINE_FIXED_PRICE && applied.stream().anyMatch(
            value -> value.actionType().equals(command.actionType().name()))) {
            throw new ServiceException("PRM-AUTH-015: 整单优惠或抹零不得重复执行", 409);
        }
        if (command.actionType() == ActionType.LINE_FIXED_PRICE && applied.stream().anyMatch(
            value -> value.actionType().equals(command.actionType().name())
                && java.util.Objects.equals(value.sourceLineId(), command.lineId()))) {
            throw new ServiceException("PRM-AUTH-016: 同一行不得重复手工改价", 409);
        }
    }

    private int stage(String action) {
        return switch (ActionType.valueOf(action)) {
            case LINE_FIXED_PRICE -> 1;
            case ORDER_AMOUNT_OFF, ORDER_PERCENT_OFF -> 2;
            case ROUNDING -> 3;
        };
    }

    private void insertEvent(ManualAuthorize command, StoredQuote quote, TrustedPrincipal principal, Policy policy,
                             String requestHash, String state, int sequence, Long approver,
                             ManualAuthorizationView result) {
        String resultJson = write(result.result());
        persistence.insertManualEvent(new ManualEventWrite(principal.tenantId(), ids.next(), command.authorizationId(),
            sequence, state, command.commandId(), requestHash, quote.quoteId(), quote.storeId(), quote.terminalId(),
            command.actionType().name(), command.lineId(), command.amountOrRate(), command.paymentMethod().name(),
            result.beforeFingerprint(), result.previewFingerprint(), result.incrementalDiscountMinor(),
            policy.policyVersionId(), policy.policySha256(), policy.withoutApprovalMinor(), policy.withApprovalMinor(),
            policy.minimumLinePayableMinor(), policy.maximumRoundingMinor(), write(policy.roundingMultiplesMinor()),
            command.reasonCode(), command.reasonText().trim(), principal.userId(), approver,
            stores.businessDate(quote.storeId(), instant(quote.businessTime())).businessDate(),
            command.correlationId(), resultJson, canonicalManualResult(result.result()).sha256(), LocalDateTime.now(clock)));
    }

    private void insertApprovalEvent(ManualApprove command, ManualEvent pending, TrustedPrincipal approver,
                                     String requestHash, ManualAuthorizationView result) {
        persistence.insertManualEvent(new ManualEventWrite(approver.tenantId(), ids.next(), pending.authorizationId(),
            pending.eventSequence() + 1, "APPLIED", command.commandId(), requestHash, pending.quoteId(),
            pending.storeId(), pending.terminalId(), pending.actionType(), pending.sourceLineId(),
            pending.amountOrRate(), pending.paymentMethod(), pending.beforeFingerprint(), pending.previewFingerprint(),
            pending.incrementalDiscountMinor(), pending.policyVersionId(), pending.policySha256(),
            pending.withoutApprovalMinor(), pending.withApprovalMinor(), pending.minimumLinePayableMinor(),
            pending.maximumRoundingMinor(), pending.roundingMultiplesJson(), "APPROVED", command.reason().trim(),
            pending.operatorUserId(), approver.userId(), pending.businessDate(), command.correlationId(),
            pending.resultJson(), pending.resultSha256(), LocalDateTime.now(clock)));
    }

    private ManualAuthorizationView replay(String tenantId, String commandType, String commandId, String requestHash) {
        StoredCommand stored = persistence.findCommand(tenantId, commandType, commandId);
        if (stored == null) return null;
        if (!stored.requestSha256().equals(requestHash)) {
            throw new ServiceException("PRM-IDEMP-003: 同一命令幂等键对应不同内容", 409);
        }
        try {
            ManualAuthorizationView result = objectMapper.readValue(stored.resultJson(), ManualAuthorizationView.class);
            if (!canonicalView(result).sha256().equals(stored.resultSha256())) {
                throw new ServiceException("PRM-IDEMP-004: 命令结果摘要不一致", 500);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-IDEMP-004: 命令结果损坏", 500);
        }
    }

    private void persistCommand(String tenantId, String commandType, String commandId, String requestHash,
                                ManualAuthorizationView result) {
        String resultJson = write(result);
        persistence.insertCommand(new CommandWrite(tenantId, ids.next(), commandType, commandId, requestHash,
            "PROMOTION_MANUAL_AUTHORIZATION", result.authorizationId(), canonicalView(result).sha256(), resultJson));
    }

    private void appendAuditAndOutbox(TrustedPrincipal principal, ManualAuthorizationView result,
                                      String correlationId, long version) {
        LocalDateTime now = LocalDateTime.now(clock);
        String action = "APPLIED".equals(result.state()) ? "PROMOTION_MANUAL_APPLIED" : "PROMOTION_MANUAL_PENDING";
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(), action,
            "PROMOTION_MANUAL_AUTHORIZATION", result.authorizationId(), principal.userId(), correlationId,
            result.beforeFingerprint(), result.previewFingerprint(),
            write(Map.of("action", action, "amountMinor", result.incrementalDiscountMinor())), now));
        CanonicalJson.Result payload = CanonicalJson.from(Map.of("authorizationId", result.authorizationId(),
            "state", result.state(), "quoteId", result.quoteId(), "amountMinor", result.incrementalDiscountMinor()));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(),
            "promotion.manual.changed.v1", "PROMOTION_MANUAL_AUTHORIZATION", result.authorizationId(), version,
            payload.json(), payload.sha256(), now));
    }

    private QuoteResult readQuoteResult(String json, String expectedHash) {
        try {
            QuoteResult result = objectMapper.readValue(json, QuoteResult.class);
            if (!canonicalManualResult(result).sha256().equals(expectedHash)) {
                throw new ServiceException("PRM-AUTH-017: 人工优惠结果摘要不一致", 500);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-AUTH-017: 人工优惠结果损坏", 500);
        }
    }

    private StoredQuote requireLockedQuote(String tenantId, String quoteId) {
        StoredQuote value = persistence.lockQuote(tenantId, quoteId);
        if (value == null) throw new ServiceException("PRM-AUTH-018: 原始报价不存在或不可见", 404);
        return value;
    }

    private void requireUnfrozen(String tenantId, String quoteId) {
        if (persistence.findSnapshotByQuote(tenantId, quoteId) != null) {
            throw new ServiceException("PRM-AUTH-021: 报价已冻结成交快照，不得继续追加人工优惠", 409);
        }
    }

    private CanonicalJson.Result canonicalAuthorize(ManualAuthorize value) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("authorizationId", value.authorizationId()); content.put("quoteId", value.quoteId());
        content.put("actionType", value.actionType().name());
        if (value.lineId() != null) content.put("lineId", value.lineId());
        content.put("amountOrRate", value.amountOrRate()); content.put("paymentMethod", value.paymentMethod().name());
        content.put("expectedQuoteFingerprint", value.expectedQuoteFingerprint());
        content.put("reasonCode", value.reasonCode()); content.put("reasonText", value.reasonText().trim());
        return CanonicalJson.from(content);
    }

    private CanonicalJson.Result canonicalApprove(ManualApprove value) {
        return CanonicalJson.from(Map.of("authorizationId", value.authorizationId(),
            "expectedPreviewFingerprint", value.expectedPreviewFingerprint(), "reason", value.reason().trim()));
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
            "lineAllocations", new java.util.TreeMap<>(item.lineAllocations()))).toList();
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", value.grossAmountMinor(), "discountAmountMinor", value.discountAmountMinor(),
            "payableAmountMinor", value.payableAmountMinor(), "lineDiscounts", value.lineDiscounts(),
            "appliedRuleIds", value.appliedRuleIds(), "adjustments", adjustments,
            "explanations", value.explanations().stream().map(item -> item.sourceId() + ":" + item.code()).toList()));
    }

    private CanonicalJson.Result canonicalView(ManualAuthorizationView value) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("authorizationId", value.authorizationId()); content.put("state", value.state());
        content.put("quoteId", value.quoteId()); content.put("actionType", value.actionType());
        content.put("operatorUserId", value.operatorUserId());
        if (value.approverUserId() != null) content.put("approverUserId", value.approverUserId());
        content.put("policyVersionId", value.policyVersionId()); content.put("beforeFingerprint", value.beforeFingerprint());
        content.put("previewFingerprint", value.previewFingerprint());
        content.put("incrementalDiscountMinor", value.incrementalDiscountMinor());
        content.put("resultFingerprint", canonicalManualResult(value.result()).sha256());
        return CanonicalJson.from(content);
    }

    private void requireAuthorize(ManualAuthorize value) {
        if (value == null) throw new ServiceException("PRM-AUTH-019: 人工优惠请求无效", 400);
        requireUlid(value.commandId()); requireUlid(value.authorizationId()); requireUlid(value.quoteId());
        requireUlid(value.correlationId()); requireSha(value.expectedQuoteFingerprint());
        if (value.actionType() == null || value.paymentMethod() == null || value.amountOrRate() == null
            || value.amountOrRate().isBlank() || value.amountOrRate().length() > 32
            || value.reasonCode() == null || !value.reasonCode().matches("^[A-Z0-9_]{1,32}$")
            || value.reasonText() == null || value.reasonText().isBlank() || value.reasonText().length() > 256
            || (value.lineId() != null && !value.lineId().matches(ULID))) {
            throw new ServiceException("PRM-AUTH-019: 人工优惠请求无效", 400);
        }
    }

    private void requireApprove(ManualApprove value) {
        if (value == null) throw new ServiceException("PRM-AUTH-020: 人工优惠复核请求无效", 400);
        requireUlid(value.commandId()); requireUlid(value.authorizationId()); requireUlid(value.correlationId());
        requireSha(value.expectedPreviewFingerprint());
        if (value.reason() == null || value.reason().isBlank() || value.reason().length() > 256) {
            throw new ServiceException("PRM-AUTH-020: 人工优惠复核请求无效", 400);
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

    private record Current(QuoteResult result, String fingerprint) { }
}
