package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.model.LotPolicyModels.PolicyView;
import com.jingshanghui.pos.catalog.application.port.LotPolicyReadPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.*;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.CommandApplied;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.ExpiryProjectionWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.LotInventoryPersistenceParams.OutboxWrite;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.LotInventoryMapper;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import org.dromara.common.core.exception.ServiceException;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证批次显式拆分、行业能力开关和调拨来源上限均失败关闭。 */
class LotInventoryServiceTest {
    private static final String EVENT = "01K2A000000000000000000001";
    private static final String SOURCE = "01K2A000000000000000000002";
    private static final String LINE = "01K2A000000000000000000003";
    private static final String WAREHOUSE = "01K2A000000000000000000004";
    private static final String LOT_A = "01K2A000000000000000000005";
    private static final String LOT_B = "01K2A000000000000000000006";
    private static final String POLICY = "01K2A000000000000000000007";
    private static final LocalDate DAY = LocalDate.of(2026, 8, 23);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T01:00:00Z"), ZoneOffset.UTC);

    private final LotInventoryMapper mapper = mock(LotInventoryMapper.class);
    private final LotPolicyReadPort policies = mock(LotPolicyReadPort.class);
    private final StoreIndustryReadPort industries = mock(StoreIndustryReadPort.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private LotInventoryService service;

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 101L, 1L, "operator"));
        service = new LotInventoryService(mapper, policies, industries, context, authorization,
            new UlidGenerator(CLOCK), new ObjectMapper().findAndRegisterModules(), CLOCK);
    }

    @Test
    void nonCommunityTemplateNeverEnablesLotPath() {
        when(industries.requireCurrentIndustry(10L)).thenReturn(binding("CONVENIENCE_STORE"));
        assertThat(service.requiresLotTracking(10L, 1001L, DAY)).isFalse();
        verifyNoInteractions(policies);
    }

    @Test
    void explicitMovementAllowsTwoLotsButChecksGenericLineOnce() {
        when(industries.requireCurrentIndustry(10L)).thenReturn(binding("COMMUNITY_SUPERMARKET"));
        when(policies.requireEffective(anyLong(), anyLong(), any())).thenReturn(policy());
        when(mapper.findGenericMovement("TENANT_A", EVENT, LINE, WAREHOUSE, 1001L))
            .thenReturn(new GenericMovementView("STOCKTAKE_LOSS", new BigDecimal("3.000000")));
        when(mapper.lockLot("TENANT_A", LOT_A)).thenReturn(lot(LOT_A, "5.000000"));
        when(mapper.lockLot("TENANT_A", LOT_B)).thenReturn(lot(LOT_B, "5.000000"));
        when(mapper.updateBalance(any())).thenReturn(1);
        when(mapper.completeCommand(any())).thenReturn(1);

        ApplyResult result = service.applyExplicit(new ExplicitCommand(source("STOCKTAKE"), "MIXED", List.of(
            new ExplicitLine(LINE, LOT_A, 1001L, 2001L, new BigDecimal("1.000000"), "STOCKTAKE_LOSS"),
            new ExplicitLine(LINE, LOT_B, 1001L, 2001L, new BigDecimal("2.000000"), "STOCKTAKE_LOSS"))));

        assertThat(result.affectedLines()).isEqualTo(2);
        verify(mapper, times(1)).findGenericMovement("TENANT_A", EVENT, LINE, WAREHOUSE, 1001L);
        verify(mapper, times(2)).insertLedger(any());
        ArgumentCaptor<CommandApplied> completed = ArgumentCaptor.forClass(CommandApplied.class);
        verify(mapper).completeCommand(completed.capture());
        assertThat(completed.getValue().affectedLines()).isEqualTo(2);
        ArgumentCaptor<OutboxWrite> outbox = ArgumentCaptor.forClass(OutboxWrite.class);
        verify(mapper).insertOutbox(outbox.capture());
        assertThat(outbox.getValue().eventType()).isEqualTo("inventory.lot.moved.v1");
    }

    @Test
    void transferReceiptCannotExceedOriginalDispatchAllocation() {
        when(mapper.findGenericMovement("TENANT_A", EVENT, LINE, WAREHOUSE, 1001L))
            .thenReturn(new GenericMovementView("TRANSFER_IN", new BigDecimal("2.000000")));
        when(mapper.lockTransferableAllocation("TENANT_A", SOURCE, LOT_B, LOT_A, 1001L))
            .thenReturn(new AllocationView(LOT_B, SOURCE, LOT_B, LOT_A, 1001L,
                new BigDecimal("1.000000"), "EXPLICIT", POLICY, DAY.plusDays(10)));

        assertThatThrownBy(() -> service.receiveTransfer(new TransferReceiveCommand(source("TRANSFER_RECEIPT"),
            SOURCE, List.of(new TransferReceiveLine(LINE, LOT_B, LOT_A, 1001L, 2001L,
                new BigDecimal("2.000000"))))))
            .isInstanceOf(ServiceException.class).hasMessageContaining("LOT-TRANSFER-003");
        verify(mapper, never()).insertLedger(any());
    }

    @Test
    void saleKeepsLotFrozenNearExpiryThresholdAfterPolicyChanges() {
        LotView frozen = lot(LOT_A, "5.000000", 1);
        when(industries.requireCurrentIndustry(10L)).thenReturn(binding("COMMUNITY_SUPERMARKET"));
        when(policies.requireEffective(anyLong(), anyLong(), any())).thenReturn(policy());
        when(mapper.findGenericMovement("TENANT_A", EVENT, LINE, WAREHOUSE, 1001L))
            .thenReturn(new GenericMovementView("SALE_OUT", new BigDecimal("1.000000")));
        when(mapper.lockFefoCandidates("TENANT_A", WAREHOUSE, 1001L, DAY, 100)).thenReturn(List.of(frozen));
        when(mapper.lockLot("TENANT_A", LOT_A)).thenReturn(frozen);
        when(mapper.updateBalance(any())).thenReturn(1);
        when(mapper.completeCommand(any())).thenReturn(1);

        service.allocateSale(new SaleCommand(source("ORDER"),
            List.of(new SaleLine(LINE, 1001L, 2001L, new BigDecimal("1.000000")))));

        ArgumentCaptor<ExpiryProjectionWrite> projection = ArgumentCaptor.forClass(ExpiryProjectionWrite.class);
        verify(mapper).upsertExpiryProjection(projection.capture());
        assertThat(projection.getValue().nearExpiryDays()).isEqualTo(1);
    }

    private static CommandSource source(String type) {
        return new CommandSource(EVENT, type, SOURCE, WAREHOUSE, 10L, DAY, "trace-lot");
    }

    private static IndustryBinding binding(String industry) {
        return new IndustryBinding(10L, 20L, 30L, 1, industry, "a".repeat(64),
            "Asia/Shanghai", LocalTime.of(4, 0));
    }

    private static PolicyView policy() {
        return new PolicyView(POLICY, 10L, 1001L, true, "EXPLICIT_EXPIRY_DATE", null, 3,
            "COMMUNITY_SUPERMARKET", 30L, Instant.parse("2026-08-01T00:00:00Z"), "b".repeat(64), "PUBLISHED");
    }

    private static LotView lot(String id, String quantity) {
        return lot(id, quantity, 3);
    }

    private static LotView lot(String id, String quantity, int nearExpiryDays) {
        return new LotView(id, 10L, WAREHOUSE, 1001L, 2001L, "SUP-1", "LOT-1", DAY.minusDays(3),
            DAY.minusDays(2), DAY.plusDays(10), POLICY, nearExpiryDays, new BigDecimal(quantity), 0L, "AVAILABLE",
            LocalDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC));
    }
}
