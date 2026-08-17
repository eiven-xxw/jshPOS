package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.ManualApprove;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.ManualAuthorize;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.ManualEvent;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.ManualEventWrite;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.ManualPolicyRow;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredAdjustment;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredQuote;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.StoredQuoteLine;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.ActionType;
import com.jingshanghui.pos.promotion.domain.ManualAdjustmentEngine.PaymentMethod;
import com.jingshanghui.pos.promotion.domain.PromotionEngine;
import com.jingshanghui.pos.promotion.infrastructure.id.PromotionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** PRM-002 应用层职责分离、可信租户、幂等事实和原始报价摘要测试。 */
class ManualPromotionServiceTest {
    private static final String TENANT = "TENANT_A";
    private static final String QUOTE = "01K5R000000000000000000001";
    private static final String LINE_1 = "01K5R000000000000000000002";
    private static final String LINE_2 = "01K5R000000000000000000003";
    private static final String AUTH = "01K5R000000000000000000004";
    private static final String COMMAND = "01K5R000000000000000000005";
    private static final String APPROVE_COMMAND = "01K5R000000000000000000006";
    private static final String CORRELATION = "01K5R000000000000000000007";
    private static final String RULE = "01K5R000000000000000000008";
    private static final Instant NOW = Instant.parse("2026-08-17T04:00:00Z");
    private static final TrustedPrincipal OPERATOR = new TrustedPrincipal(TENANT, 7L, 70L, "synthetic-operator");
    private static final TrustedPrincipal APPROVER = new TrustedPrincipal(TENANT, 8L, 80L, "synthetic-approver");

    private final TrustedTenantContext tenants = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final StoreService stores = mock(StoreService.class);
    private final PromotionPersistencePort persistence = mock(PromotionPersistencePort.class);
    private final PromotionIdGenerator ids = mock(PromotionIdGenerator.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private ManualPromotionService service;
    private String baseFingerprint;

    @BeforeEach
    void setUp() {
        baseFingerprint = baseFingerprint();
        when(tenants.requirePrincipal()).thenReturn(OPERATOR);
        when(ids.next()).thenReturn(
            "01K5R000000000000000000101", "01K5R000000000000000000102",
            "01K5R000000000000000000103", "01K5R000000000000000000104",
            "01K5R000000000000000000105", "01K5R000000000000000000106",
            "01K5R000000000000000000107", "01K5R000000000000000000108");
        when(persistence.lockQuote(TENANT, QUOTE)).thenReturn(new StoredQuote(QUOTE, 1101L, "TERM-01",
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "CNY", "c".repeat(64), baseFingerprint,
            PromotionEngine.ENGINE_VERSION, 31L, 1000L, 100L, 900L));
        when(persistence.listQuoteLines(TENANT, QUOTE)).thenReturn(List.of(
            new StoredQuoteLine(LINE_1, 1, 101L, new BigDecimal("2.000000"), 600, 60, 540),
            new StoredQuoteLine(LINE_2, 2, 102L, new BigDecimal("1.000000"), 400, 40, 360)));
        when(persistence.listQuoteAdjustments(TENANT, QUOTE)).thenReturn(List.of(
            new StoredAdjustment(LINE_1, "RULE", RULE, "RULE", 60, "APPLIED", true, 1),
            new StoredAdjustment(LINE_2, "RULE", RULE, "RULE", 40, "APPLIED", true, 2)));
        when(persistence.listAppliedManualEvents(TENANT, QUOTE)).thenReturn(List.of());
        when(persistence.findManualPolicy(TENANT, 1101L)).thenReturn(policy());
        when(stores.businessDate(eq(1101L), any())).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        service = new ManualPromotionService(tenants, authorization, stores, persistence,
            new ManualAdjustmentEngine(), new ManualPolicyCodec(objectMapper), ids, objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void belowThresholdAppliesAtomicallyAndWritesAuditOutboxAndCommandResult() {
        var result = service.authorize(authorize(ActionType.ORDER_AMOUNT_OFF, null, "90"));

        assertThat(result.state()).isEqualTo("APPLIED");
        assertThat(result.operatorUserId()).isEqualTo(7L);
        assertThat(result.approverUserId()).isNull();
        assertThat(result.incrementalDiscountMinor()).isEqualTo(90L);
        verify(authorization).requireStoreAccess(1101L);
        verify(persistence).insertManualEvent(argThat(value -> TENANT.equals(value.tenantId())
            && "APPLIED".equals(value.state()) && value.approverUserId() == null));
        verify(persistence).insertCommand(argThat(value -> "MANUAL_AUTHORIZE".equals(value.commandType())));
        verify(persistence).insertAudit(argThat(value -> "PROMOTION_MANUAL_APPLIED".equals(value.actionCode())));
        verify(persistence).insertOutbox(argThat(value -> "promotion.manual.changed.v1".equals(value.eventType())));
    }

    @Test
    void overThresholdRequiresDifferentAuthenticatedApproverAndPreservesPreview() {
        when(tenants.requirePrincipal()).thenReturn(OPERATOR, APPROVER);
        var pending = service.authorize(authorize(ActionType.LINE_FIXED_PRICE, LINE_1, "200"));
        assertThat(pending.state()).isEqualTo("PENDING_APPROVAL");

        ArgumentCaptor<ManualEventWrite> event = ArgumentCaptor.forClass(ManualEventWrite.class);
        verify(persistence).insertManualEvent(event.capture());
        when(persistence.findLatestManualEvent(TENANT, AUTH)).thenReturn(stored(event.getValue()));

        var approved = service.approve(new ManualApprove(APPROVE_COMMAND, AUTH,
            pending.previewFingerprint(), "主管复核通过", CORRELATION));

        assertThat(approved.state()).isEqualTo("APPLIED");
        assertThat(approved.operatorUserId()).isEqualTo(7L);
        assertThat(approved.approverUserId()).isEqualTo(8L);
        assertThat(approved.result()).isEqualTo(pending.result());
        verify(persistence, times(2)).insertManualEvent(any());
        verify(persistence).insertCommand(argThat(value -> "MANUAL_APPROVE".equals(value.commandType())));
    }

    @Test
    void sameAuthenticatedUserCannotApproveOwnPendingAuthorization() {
        var pending = service.authorize(authorize(ActionType.LINE_FIXED_PRICE, LINE_1, "200"));
        ArgumentCaptor<ManualEventWrite> event = ArgumentCaptor.forClass(ManualEventWrite.class);
        verify(persistence).insertManualEvent(event.capture());
        when(persistence.findLatestManualEvent(TENANT, AUTH)).thenReturn(stored(event.getValue()));

        assertThatThrownBy(() -> service.approve(new ManualApprove(APPROVE_COMMAND, AUTH,
            pending.previewFingerprint(), "本人复核", CORRELATION))).hasMessageContaining("PRM-AUTH-007");
        verify(persistence, times(1)).insertManualEvent(any());
    }

    @Test
    void corruptedOriginalQuoteFingerprintFailsBeforeAnyManualFactIsWritten() {
        when(persistence.lockQuote(TENANT, QUOTE)).thenReturn(new StoredQuote(QUOTE, 1101L, "TERM-01",
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "CNY", "c".repeat(64), "f".repeat(64),
            PromotionEngine.ENGINE_VERSION, 31L, 1000L, 100L, 900L));

        assertThatThrownBy(() -> service.authorize(authorize(ActionType.ORDER_AMOUNT_OFF, null, "90")))
            .hasMessageContaining("PRM-AUTH-009");
        verify(persistence, never()).insertManualEvent(any());
    }

    private ManualAuthorize authorize(ActionType type, String lineId, String amountOrRate) {
        return new ManualAuthorize(COMMAND, AUTH, QUOTE, type, lineId, amountOrRate, PaymentMethod.NON_CASH,
            baseFingerprint, "CUSTOMER_CARE", "合成测试人工优惠", CORRELATION);
    }

    private ManualPolicyRow policy() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("policyType", "PROMOTION_MANUAL_AUTHORITY");
        content.put("withoutApprovalMinor", 100L);
        content.put("withApprovalMinor", 1000L);
        content.put("minimumLinePayableMinor", 20L);
        content.put("maximumRoundingMinor", 9L);
        content.put("roundingMultiplesMinor", List.of(1L, 10L));
        CanonicalJson.Result result = CanonicalJson.from(content);
        return new ManualPolicyRow(31L, result.sha256(), result.json());
    }

    private String baseFingerprint() {
        Map<String, Long> lineDiscounts = new LinkedHashMap<>();
        lineDiscounts.put(LINE_1, 60L);
        lineDiscounts.put(LINE_2, 40L);
        return CanonicalJson.from(Map.of("engineVersion", PromotionEngine.ENGINE_VERSION,
            "grossAmountMinor", 1000L, "discountAmountMinor", 100L, "payableAmountMinor", 900L,
            "lineDiscounts", lineDiscounts, "appliedRuleIds", List.of(RULE))).sha256();
    }

    private ManualEvent stored(ManualEventWrite value) {
        return new ManualEvent(value.authorizationId(), value.eventSequence(), value.state(), value.quoteId(),
            value.storeId(), value.terminalId(), value.actionType(), value.sourceLineId(), value.amountOrRate(),
            value.paymentMethod(), value.beforeFingerprint(), value.previewFingerprint(),
            value.incrementalDiscountMinor(), value.policyVersionId(), value.policySha256(),
            value.withoutApprovalMinor(), value.withApprovalMinor(), value.minimumLinePayableMinor(),
            value.maximumRoundingMinor(), value.roundingMultiplesJson(), value.operatorUserId(),
            value.approverUserId(), value.businessDate(), value.resultJson(), value.resultSha256());
    }
}
