package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.CreateRule;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.Quote;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.StateCommand;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.PackageView;
import com.jingshanghui.pos.promotion.application.model.PromotionViews.RuleVersionView;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.CommandWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredCommand;
import com.jingshanghui.pos.promotion.application.port.PromotionRuleRepository;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证可信租户、命令幂等、状态职责分离与指定规则包冻结询价。 */
class PromotionServiceTest {
    private static final String TENANT = "TENANT_A";
    private static final String RULE = "01K5R000000000000000000001";
    private static final String VERSION = "01K5R000000000000000000002";
    private static final String COMMAND = "01K5R000000000000000000003";
    private static final String CORRELATION = "01K5R000000000000000000004";
    private static final String LINE = "01K5R000000000000000000005";
    private static final Instant NOW = Instant.parse("2026-08-17T02:00:00Z");

    private final TrustedTenantContext tenants = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final PromotionRuleRepository rules = mock(PromotionRuleRepository.class);
    private final PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
    private final PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PromotionService service;

    @BeforeEach
    void setUp() {
        TrustedPrincipal principal = new TrustedPrincipal(TENANT, 7L, 8L, "synthetic-user");
        when(tenants.requirePrincipal()).thenReturn(principal);
        when(tenants.requireTenantId()).thenReturn(TENANT);
        when(ids.next()).thenReturn("01K5R000000000000000000100", "01K5R000000000000000000101",
            "01K5R000000000000000000102", "01K5R000000000000000000103",
            "01K5R000000000000000000104", "01K5R000000000000000000105");
        service = new PromotionService(tenants, authorization, rules, persistence, new PromotionEngine(), ids,
            objectMapper, new PromotionRuleDefinitionCodec(objectMapper), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createWritesRuleFactsAuditOutboxAndReplayableCommandResult() {
        RuleVersion definition = rule();
        RuleVersionView stored = view("DRAFT", 0, 7L, null);
        when(persistence.findVersion(TENANT, RULE, VERSION)).thenReturn(stored);

        RuleVersionView result = service.create(new CreateRule(COMMAND, RULE, VERSION, "WEEKEND_10_OFF",
            "周末直减", definition, CORRELATION));

        assertThat(result).isEqualTo(stored);
        verify(rules).insert(argThat(value -> TENANT.equals(value.tenantId()) && RULE.equals(value.ruleId())));
        verify(persistence).insertVersion(argThat(value -> TENANT.equals(value.tenantId())
            && "DRAFT".equals(value.state())));
        verify(persistence).insertAudit(any());
        verify(persistence).insertOutbox(any());
        verify(persistence).insertCommand(argThat(value -> "CREATE_RULE".equals(value.commandType())
            && COMMAND.equals(value.idempotencyKey())));
        verify(authorization).requireStoreAccess(1101L);
    }

    @Test
    void stateCommandReplaysOriginalResultAndRejectsSameKeyDifferentContent() {
        RuleVersionView draft = view("DRAFT", 0, 7L, null);
        RuleVersionView validated = view("VALIDATED", 1, 7L, null);
        when(persistence.findVersion(TENANT, RULE, VERSION)).thenReturn(draft, validated);
        when(persistence.changeState(any())).thenReturn(1);
        when(persistence.findRuleDefinition(TENANT, VERSION)).thenReturn(row());

        StateCommand command = new StateCommand(COMMAND, RULE, VERSION, 0, "静态预检", CORRELATION);
        assertThat(service.validate(command).state()).isEqualTo("VALIDATED");
        ArgumentCaptor<CommandWrite> capture = ArgumentCaptor.forClass(CommandWrite.class);
        verify(persistence).insertCommand(capture.capture());
        CommandWrite written = capture.getValue();
        when(persistence.findCommand(TENANT, "VALIDATED_RULE", COMMAND)).thenReturn(new StoredCommand(
            written.requestSha256(), written.aggregateId(), written.resultSha256(), written.resultJson()));

        assertThat(service.validate(command)).isEqualTo(validated);
        verify(persistence, times(1)).changeState(any());
        assertThatThrownBy(() -> service.validate(new StateCommand(COMMAND, RULE, VERSION, 0,
            "不同内容", CORRELATION))).hasMessageContaining("PRM-IDEMP-003");
    }

    @Test
    void creatorCannotApproveOwnRuleAndNoStateMutationOccurs() {
        when(persistence.findVersion(TENANT, RULE, VERSION)).thenReturn(view("VALIDATED", 1, 7L, null));
        assertThatThrownBy(() -> service.approve(new StateCommand(COMMAND, RULE, VERSION, 1,
            "审批", CORRELATION))).hasMessageContaining("PRM-RBAC-001");
        verify(persistence, never()).changeState(any());
    }

    @Test
    void quoteUsesExactPackageMembershipAndFailsClosedOutsidePackageWindow() {
        OffsetDateTime businessTime = OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC);
        when(persistence.findPackage(TENANT, 1101L, 3L)).thenReturn(new PackageView(
            "01K5R000000000000000000010", 1101L, 3L, 2L, "a".repeat(64), "synthetic-key-v1",
            "tenant/TENANT_A/object/package", businessTime.minusMinutes(1).toLocalDateTime(),
            businessTime.plusMinutes(10).toLocalDateTime()));
        when(persistence.listPackageRules(TENANT, 1101L, 3L)).thenReturn(List.of(row()));
        Quote quote = new Quote(COMMAND, 1101L, "TERM-01", "POS", businessTime, "CNY", 3L,
            List.of(new BasketLine(LINE, 1, 1L, null, null, BigDecimal.ONE, 100L)), CORRELATION);

        assertThat(service.quote(quote).result().discountAmountMinor()).isEqualTo(10L);
        verify(persistence).listPackageRules(TENANT, 1101L, 3L);
        verify(persistence, never()).listPublishedRules(anyString(), anyLong(), anyString(), any());

        reset(persistence);
        when(persistence.findPackage(TENANT, 1101L, 3L)).thenReturn(new PackageView(
            "01K5R000000000000000000010", 1101L, 3L, 2L, "a".repeat(64), "synthetic-key-v1",
            "tenant/TENANT_A/object/package", businessTime.minusHours(2).toLocalDateTime(),
            businessTime.minusHours(1).toLocalDateTime()));
        assertThatThrownBy(() -> service.quote(quote)).hasMessageContaining("PRM-QUOTE-002");
        verify(persistence, never()).insertQuote(any());
    }

    private static RuleVersion rule() {
        return new RuleVersion(VERSION, RuleType.AMOUNT_OFF, 100, StackMode.STACKABLE, null,
            OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC),
            OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC),
            new RuleScope(Set.of(1L), Set.of(), Set.of(), Set.of(1101L), Set.of("POS"), Set.of(1)),
            new RuleBenefit(10L, null, null, null, null, null, List.of()));
    }

    private static PromotionPersistencePort.PublishedRuleRow row() {
        RuleVersion value = rule();
        return new PromotionPersistencePort.PublishedRuleRow(VERSION, value.ruleType().name(), value.priority(),
            value.stackMode().name(), null, value.effectiveFrom().toLocalDateTime(),
            value.effectiveTo().toLocalDateTime(), "SKU:1|STORE:1101|CHANNEL:POS|BUSINESS_DAY:1", 10L,
            null, null, null, null, null, "[]");
    }

    private static RuleVersionView view(String state, int optimisticVersion, Long creator, Long approver) {
        return new RuleVersionView(RULE, VERSION, "WEEKEND_10_OFF", "周末直减", 1, state, creator,
            approver, optimisticVersion, "b".repeat(64));
    }
}
