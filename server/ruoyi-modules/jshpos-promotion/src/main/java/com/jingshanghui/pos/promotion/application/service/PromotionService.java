package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.*;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.*;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.*;
import com.jingshanghui.pos.promotion.application.port.PromotionRuleRepository;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.domain.PromotionRuleValidator;
import com.jingshanghui.pos.promotion.domain.PromotionStates;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Gate 5A 促销规则生命周期和确定性询价应用服务。 */
@Service
@RequiredArgsConstructor
public class PromotionService {
    private static final String ULID = "^[0-9A-HJKMNP-TV-Z]{26}$";
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;
    private final PromotionRuleRepository rules;
    private final PromotionPersistencePort persistence;
    private final PromotionEngine engine;
    private final PromotionIdGenerator ids;
    private final ObjectMapper objectMapper;
    private final PromotionRuleDefinitionCodec definitionCodec;
    private final Clock clock;

    /** 创建规则身份和首个草稿版本。 */
    @Transactional
    public RuleVersionView create(CreateRule command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireCreate(command);
        if (!command.ruleVersionId().equals(command.definition().ruleVersionId())) {
            throw new ServiceException("PRM-RULE-003: 命令和定义的规则版本不一致", 400);
        }
        command.definition().scope().storeIds().forEach(authorization::requireStoreAccess);
        PromotionRuleValidator.validate(command.definition());
        CanonicalJson.Result canonical = definitionCodec.canonical(command.definition());
        CanonicalJson.Result commandCanonical = CanonicalJson.from(Map.of("ruleId", command.ruleId(),
            "ruleVersionId", command.ruleVersionId(), "ruleCode", command.ruleCode(),
            "name", command.name().trim(), "contentSha256", canonical.sha256()));
        RuleVersionView commandReplay = replayRuleCommand(principal.tenantId(), "CREATE_RULE", command.commandId(),
            commandCanonical.sha256());
        if (commandReplay != null) return commandReplay;
        PromotionRuleRepository.RuleIdentity existing = rules.find(principal.tenantId(), command.ruleId());
        if (existing != null) {
            RuleVersionView replay = persistence.findVersion(principal.tenantId(), command.ruleId(),
                command.ruleVersionId());
            if (replay != null && replay.contentSha256().equals(canonical.sha256())) {
                persistRuleCommand(principal.tenantId(), "CREATE_RULE", command.commandId(), commandCanonical.sha256(), replay);
                return replay;
            }
            throw new ServiceException("PRM-IDEMP-001: 同一规则标识对应不同内容", 409);
        }
        rules.insert(new PromotionRuleRepository.RuleIdentity(principal.tenantId(), command.ruleId(),
            command.ruleCode(), command.name().trim(), "ACTIVE", principal.userId()));
        RuleVersion definition = command.definition();
        persistence.insertVersion(new VersionWrite(principal.tenantId(), command.ruleVersionId(), command.ruleId(),
            1, definition.ruleType().name(), definition.priority(), definition.stackMode().name(),
            definition.exclusiveGroup(), utc(definition.effectiveFrom()), utc(definition.effectiveTo()), "DRAFT",
            canonical.sha256(), PromotionEngine.ENGINE_VERSION, principal.userId()));
        persistScopes(principal.tenantId(), definition);
        persistBenefit(principal.tenantId(), definition);
        appendAuditAndOutbox(principal, "PROMOTION_RULE_CREATED", command.ruleVersionId(), null,
            canonical.sha256(), command.correlationId(), 1);
        RuleVersionView result = requireVersion(principal.tenantId(), command.ruleId(), command.ruleVersionId());
        persistRuleCommand(principal.tenantId(), "CREATE_RULE", command.commandId(), commandCanonical.sha256(), result);
        return result;
    }

    /** 将草稿变为已经通过静态预检的版本。 */
    @Transactional
    public RuleVersionView validate(StateCommand command) {
        return transition(command, "VALIDATED", current -> {
            PublishedRuleRow stored = persistence.findRuleDefinition(tenantContext.requireTenantId(),
                command.ruleVersionId());
            if (stored == null) throw new ServiceException("PRM-RULE-007: 规则版本不存在或不可见", 404);
            PromotionRuleValidator.validate(definitionCodec.fromRow(stored));
        });
    }

    /** 审批已预检版本，并强制创建人与审批人分离。 */
    @Transactional
    public RuleVersionView approve(StateCommand command) {
        return transition(command, "APPROVED", current -> {
            if (Objects.equals(current.creatorUserId(), tenantContext.requirePrincipal().userId())) {
                throw new ServiceException("PRM-RBAC-001: 规则创建人不得审批自己的版本", 403);
            }
        });
    }

    /** 发布已审批且内容不可变的规则版本。 */
    @Transactional
    public RuleVersionView publish(StateCommand command) { return transition(command, "PUBLISHED", ignored -> { }); }

    /** 暂停新询价继续使用规则版本。 */
    @Transactional
    public RuleVersionView pause(StateCommand command) { return transition(command, "PAUSED", ignored -> { }); }

    /** 驳回尚未发布的版本；历史内容保留供审计。 */
    @Transactional
    public RuleVersionView reject(StateCommand command) { return transition(command, "REJECTED", ignored -> { }); }

    /** 退役已发布或已暂停版本；只影响后续规则包。 */
    @Transactional
    public RuleVersionView retire(StateCommand command) { return transition(command, "RETIRED", ignored -> { }); }

    /** 使用可信租户和门店范围内的已发布规则执行并持久化询价。 */
    @Transactional
    public QuoteView quote(Quote command) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireQuote(command);
        authorization.requireStoreAccess(command.storeId());
        String requestHash = canonicalQuote(command).sha256();
        StoredQuote replay = persistence.findQuoteByKey(principal.tenantId(), command.storeId(),
            command.terminalId(), command.pricingRequestId());
        if (replay != null) return replay(command, replay, requestHash);
        PackageView packageView = persistence.findPackage(principal.tenantId(), command.storeId(),
            command.packageVersion());
        LocalDateTime businessTime = utc(command.businessTime());
        if (packageView == null || businessTime.isBefore(packageView.generatedAt())
            || !businessTime.isBefore(packageView.expiresAt())) {
            throw new ServiceException("PRM-QUOTE-002: 指定规则包不存在、不适用或已过期", 409);
        }
        List<RuleVersion> published = persistence.listPackageRules(principal.tenantId(), command.storeId(),
            command.packageVersion()).stream().map(definitionCodec::fromRow).toList();
        QuoteResult result = engine.quote(new QuoteRequest(command.businessTime(), command.storeId(),
            command.channel(), command.lines(), published));
        String resultHash = canonicalResult(result).sha256();
        String quoteId = ids.next();
        persistence.insertQuote(new QuoteWrite(principal.tenantId(), quoteId, command.storeId(),
            command.terminalId(), command.pricingRequestId(), requestHash, PromotionEngine.ENGINE_VERSION,
            command.packageVersion(), utc(command.businessTime()), result.grossAmountMinor(),
            result.discountAmountMinor(), result.payableAmountMinor(), command.currency(), resultHash));
        Map<String, BasketLine> inputs = new HashMap<>();
        command.lines().forEach(line -> inputs.put(line.lineId(), line));
        for (QuoteLine line : result.lines()) {
            BasketLine input = inputs.get(line.lineId());
            persistence.insertQuoteLine(new QuoteLineWrite(principal.tenantId(), ids.next(), quoteId,
                line.lineId(), input.lineNo(), input.skuId(), input.quantity(), input.unitPriceMinor(),
                line.grossAmountMinor(), line.discountAmountMinor(), line.payableAmountMinor()));
        }
        int ordinal = 0;
        for (AppliedAdjustment adjustment : result.adjustments()) {
            for (Map.Entry<String, Long> allocation : adjustment.lineAllocations().entrySet()) {
                persistence.insertAdjustment(new AdjustmentWrite(principal.tenantId(), ids.next(), quoteId,
                    allocation.getKey(), "RULE", adjustment.sourceId(), "PROMOTION", allocation.getValue(),
                    "APPLIED", true, ++ordinal));
            }
        }
        for (Explanation explanation : result.explanations()) {
            if (!"APPLIED".equals(explanation.code())) persistence.insertAdjustment(new AdjustmentWrite(
                principal.tenantId(), ids.next(), quoteId, null, "RULE", explanation.sourceId(), "PROMOTION", 0,
                explanation.code(), false, ++ordinal));
        }
        appendAuditAndOutbox(principal, "PROMOTION_QUOTE_CREATED", quoteId, null, resultHash,
            command.correlationId(), 1);
        return new QuoteView(quoteId, requestHash, resultHash, PromotionEngine.ENGINE_VERSION,
            command.packageVersion(), result);
    }

    private RuleVersionView transition(StateCommand command, String target, Consumer<RuleVersionView> precondition) {
        TrustedPrincipal principal = tenantContext.requirePrincipal();
        requireState(command);
        CanonicalJson.Result canonical = CanonicalJson.from(Map.of("ruleId", command.ruleId(),
            "ruleVersionId", command.ruleVersionId(), "expectedVersion", command.expectedVersion(),
            "reason", command.reason().trim(), "target", target));
        String commandType = target + "_RULE";
        RuleVersionView replay = replayRuleCommand(principal.tenantId(), commandType, command.commandId(),
            canonical.sha256());
        if (replay != null) return replay;
        RuleVersionView current = requireVersion(principal.tenantId(), command.ruleId(), command.ruleVersionId());
        precondition.accept(current);
        PromotionStates.requireTransition(current.state(), target);
        if (persistence.changeState(new StateUpdate(principal.tenantId(), command.ruleId(), command.ruleVersionId(),
            current.state(), target, command.expectedVersion(), principal.userId(), LocalDateTime.now(clock))) != 1) {
            throw new ServiceException("PRM-STATE-002: 规则版本状态或乐观锁冲突", 409);
        }
        RuleVersionView after = requireVersion(principal.tenantId(), command.ruleId(), command.ruleVersionId());
        appendAuditAndOutbox(principal, "PROMOTION_RULE_" + target, command.ruleVersionId(), current.contentSha256(),
            after.contentSha256(), command.correlationId(), after.version());
        persistRuleCommand(principal.tenantId(), commandType, command.commandId(), canonical.sha256(), after);
        return after;
    }

    private RuleVersionView replayRuleCommand(String tenantId, String commandType, String commandId,
                                              String requestSha256) {
        StoredCommand stored = persistence.findCommand(tenantId, commandType, commandId);
        if (stored == null) return null;
        if (!stored.requestSha256().equals(requestSha256)) {
            throw new ServiceException("PRM-IDEMP-003: 同一命令幂等键对应不同内容", 409);
        }
        try {
            RuleVersionView result = objectMapper.readValue(stored.resultJson(), RuleVersionView.class);
            if (!canonicalRuleView(result).sha256().equals(stored.resultSha256())) {
                throw new ServiceException("PRM-IDEMP-004: 命令结果摘要不一致", 500);
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-IDEMP-004: 命令结果损坏", 500);
        }
    }

    private void persistRuleCommand(String tenantId, String commandType, String commandId,
                                    String requestSha256, RuleVersionView result) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            String resultSha256 = canonicalRuleView(result).sha256();
            persistence.insertCommand(new CommandWrite(tenantId, ids.next(), commandType, commandId,
                requestSha256, "PROMOTION_RULE", result.ruleVersionId(), resultSha256, resultJson));
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-IDEMP-005: 命令结果无法序列化", 500);
        }
    }

    private void persistScopes(String tenantId, RuleVersion definition) {
        writeScope(tenantId, definition.ruleVersionId(), "SKU", definition.scope().skuIds());
        writeScope(tenantId, definition.ruleVersionId(), "CATEGORY", definition.scope().categoryIds());
        writeScope(tenantId, definition.ruleVersionId(), "BRAND", definition.scope().brandIds());
        writeScope(tenantId, definition.ruleVersionId(), "STORE", definition.scope().storeIds());
        writeScope(tenantId, definition.ruleVersionId(), "CHANNEL", definition.scope().channels());
        writeScope(tenantId, definition.ruleVersionId(), "BUSINESS_DAY", definition.scope().businessDays());
    }

    private void writeScope(String tenantId, String versionId, String type, Collection<?> values) {
        values.stream().map(String::valueOf).sorted().forEach(value -> persistence.insertScope(
            new ScopeWrite(tenantId, ids.next(), versionId, type, value)));
    }

    private void persistBenefit(String tenantId, RuleVersion definition) {
        RuleBenefit value = definition.benefit();
        try {
            persistence.insertBenefit(new BenefitWrite(tenantId, ids.next(), definition.ruleVersionId(),
                value.amountMinor(), value.discountRate(), value.nth(), value.thresholdMinor(),
                value.thresholdQuantity(), value.bundlePriceMinor(), objectMapper.writeValueAsString(
                    value.bundleComponents().stream().map(item -> Map.of("skuId", item.skuId(),
                        "quantity", item.quantity().toPlainString())).toList())));
        } catch (JsonProcessingException exception) {
            throw new ServiceException("PRM-RULE-004: 组合组件无法序列化", 400);
        }
    }

    private QuoteView replay(Quote command, StoredQuote stored, String requestHash) {
        if (!stored.requestSha256().equals(requestHash)) {
            throw new ServiceException("PRM-IDEMP-002: 同一询价幂等键对应不同内容", 409);
        }
        List<QuoteLine> lines = persistence.listQuoteLines(tenantContext.requireTenantId(), stored.quoteId()).stream()
            .map(line -> new QuoteLine(line.sourceLineId(), line.grossAmountMinor(), line.discountAmountMinor(),
                line.payableAmountMinor())).toList();
        QuoteResult result = new QuoteResult(stored.grossAmountMinor(), stored.discountAmountMinor(),
            stored.payableAmountMinor(), lines, List.of(), List.of(), List.of());
        return new QuoteView(stored.quoteId(), stored.requestSha256(), stored.resultSha256(),
            stored.engineVersion(), stored.packageVersion(), result);
    }

    private void appendAuditAndOutbox(TrustedPrincipal principal, String action, String targetId,
                                      String before, String after, String correlationId, long version) {
        LocalDateTime now = LocalDateTime.now(clock);
        String summary = "{\"action\":\"" + action + "\"}";
        persistence.insertAudit(new AuditWrite(principal.tenantId(), ids.next(), action, "PROMOTION", targetId,
            principal.userId(), correlationId, before, after, summary, now));
        CanonicalJson.Result payload = CanonicalJson.from(Map.of("action", action, "targetId", targetId,
            "version", version));
        persistence.insertOutbox(new OutboxWrite(principal.tenantId(), ids.next(), "promotion.changed.v1",
            "PROMOTION", targetId, version, payload.json(), payload.sha256(), now));
    }

    private RuleVersionView requireVersion(String tenantId, String ruleId, String versionId) {
        RuleVersionView value = persistence.findVersion(tenantId, ruleId, versionId);
        if (value == null) throw new ServiceException("PRM-RULE-007: 规则版本不存在或不可见", 404);
        return value;
    }

    private CanonicalJson.Result canonicalQuote(Quote value) {
        List<Map<String, Object>> lines = value.lines().stream().map(line -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("lineId", line.lineId()); item.put("lineNo", line.lineNo()); item.put("skuId", line.skuId());
            if (line.categoryId() != null) item.put("categoryId", line.categoryId());
            if (line.brandId() != null) item.put("brandId", line.brandId());
            item.put("quantity", line.quantity().toPlainString()); item.put("unitPriceMinor", line.unitPriceMinor());
            return item;
        }).toList();
        return CanonicalJson.from(Map.of("storeId", value.storeId(), "terminalId", value.terminalId(),
            "channel", value.channel(), "businessTime", value.businessTime().toString(), "currency", value.currency(),
            "packageVersion", value.packageVersion(), "lines", lines));
    }

    private CanonicalJson.Result canonicalResult(QuoteResult value) {
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", value.grossAmountMinor(), "discountAmountMinor", value.discountAmountMinor(),
            "payableAmountMinor", value.payableAmountMinor(), "lineDiscounts", value.lineDiscounts(),
            "appliedRuleIds", value.appliedRuleIds()));
    }

    private CanonicalJson.Result canonicalRuleView(RuleVersionView value) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("ruleId", value.ruleId()); content.put("ruleVersionId", value.ruleVersionId());
        content.put("ruleCode", value.ruleCode()); content.put("ruleName", value.ruleName());
        content.put("versionNo", value.versionNo()); content.put("state", value.state());
        content.put("creatorUserId", value.creatorUserId());
        if (value.approvedBy() != null) content.put("approvedBy", value.approvedBy());
        content.put("version", value.version()); content.put("contentSha256", value.contentSha256());
        return CanonicalJson.from(content);
    }

    private void requireCreate(CreateRule command) {
        if (command == null) throw new ServiceException("PRM-RULE-008: 创建参数无效", 400);
        requireUlid(command.commandId(), "命令"); requireUlid(command.ruleId(), "规则");
        requireUlid(command.ruleVersionId(), "规则版本"); requireUlid(command.correlationId(), "关联标识");
        if (command.ruleCode() == null || !command.ruleCode().matches("^[A-Z0-9][A-Z0-9_-]{0,63}$")
            || command.name() == null || command.name().isBlank() || command.name().length() > 128
            || command.definition() == null) throw new ServiceException("PRM-RULE-008: 创建参数无效", 400);
    }

    private void requireQuote(Quote command) {
        if (command == null) throw new ServiceException("PRM-QUOTE-001: 询价上下文无效", 400);
        requireUlid(command.pricingRequestId(), "询价"); requireUlid(command.correlationId(), "关联标识");
        if (command.storeId() == null || command.storeId() <= 0 || command.terminalId() == null
            || command.terminalId().isBlank() || command.terminalId().length() > 64
            || command.businessTime() == null || command.lines() == null || command.lines().isEmpty()
            || command.lines().size() > 500
            || !Set.of("POS", "MOBILE_POS", "SELF_CHECKOUT").contains(command.channel())
            || !"CNY".equals(command.currency()) || command.packageVersion() <= 0) {
            throw new ServiceException("PRM-QUOTE-001: 询价上下文无效", 400);
        }
    }

    private void requireState(StateCommand command) {
        if (command == null) throw new ServiceException("PRM-STATE-003: 状态命令无效", 400);
        requireUlid(command.commandId(), "命令"); requireUlid(command.ruleId(), "规则");
        requireUlid(command.ruleVersionId(), "规则版本"); requireUlid(command.correlationId(), "关联标识");
        if (command.expectedVersion() < 0 || command.reason() == null || command.reason().isBlank()
            || command.reason().length() > 256) {
            throw new ServiceException("PRM-STATE-003: 状态命令无效", 400);
        }
    }

    private void requireUlid(String value, String field) {
        if (value == null || !value.matches(ULID)) throw new ServiceException("PRM-INPUT-001: " + field + "ULID无效", 400);
    }
    private LocalDateTime utc(OffsetDateTime value) { return value == null ? null : LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC); }
}
