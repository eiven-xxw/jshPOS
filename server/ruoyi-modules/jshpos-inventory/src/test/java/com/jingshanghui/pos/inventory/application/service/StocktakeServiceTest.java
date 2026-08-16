package com.jingshanghui.pos.inventory.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort;
import com.jingshanghui.pos.foundation.application.context.TrustedPrincipal;
import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.model.FoundationViews.BusinessDateView;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.foundation.application.service.StoreService;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.ApplyResult;
import com.jingshanghui.pos.inventory.application.model.InventoryViews.BalanceView;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Approve;
import com.jingshanghui.pos.inventory.application.model.StocktakeCommands.Submit;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Head;
import com.jingshanghui.pos.inventory.application.model.StocktakeViews.Line;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovement;
import com.jingshanghui.pos.inventory.domain.InventoryStates.MovementType;
import com.jingshanghui.pos.inventory.infrastructure.persistence.StocktakePersistenceParams.LineCutoffUpdate;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.InventoryMapper;
import com.jingshanghui.pos.inventory.infrastructure.persistence.mapper.StocktakeMapper;
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

/** 验证盲盘、动态截止、零差异和差异流水职责边界。 */
class StocktakeServiceTest {

    private static final String STOCKTAKE = "01K2A000000000000000000001";
    private static final String LINE = "01K2A000000000000000000002";
    private static final String EVENT = "01K2A000000000000000000003";
    private static final String WAREHOUSE = "01K2A000000000000000000010";
    private static final Instant NOW = Instant.parse("2026-08-17T01:00:00Z");
    private final StocktakeMapper mapper = mock(StocktakeMapper.class);
    private final InventoryMapper inventoryMapper = mock(InventoryMapper.class);
    private final TrustedTenantContext context = mock(TrustedTenantContext.class);
    private final ScopeAuthorizationService authorization = mock(ScopeAuthorizationService.class);
    private final InventoryCatalogSnapshotPort catalog = mock(InventoryCatalogSnapshotPort.class);
    private final AuthoritativeInventoryMovementPort movement = mock(AuthoritativeInventoryMovementPort.class);
    private final StoreService stores = mock(StoreService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private StocktakeService service;

    @BeforeEach
    void setUp() {
        when(context.requireTenantId()).thenReturn("TENANT_A");
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 303L, 1L, "approver"));
        service = new StocktakeService(mapper, inventoryMapper, context, authorization, catalog, movement,
            stores, new UlidGenerator(clock), new ObjectMapper(), clock);
    }

    @Test
    void blindCountProjectionHidesBookSnapshotUntilReview() {
        Head head = head("COUNTING", true, 0, 101L, null, null);
        when(mapper.findHead("TENANT_A", STOCKTAKE)).thenReturn(head);
        when(mapper.findLines("TENANT_A", STOCKTAKE)).thenReturn(List.of(line(new BigDecimal("10"),
            new BigDecimal("9"), null, 1)));

        var detail = service.detail(STOCKTAKE);

        assertThat(detail.lines().get(0).snapshotQuantity()).isNull();
        assertThat(detail.lines().get(0).snapshotLedgerSequence()).isZero();
        assertThat(detail.lines().get(0).countedQuantity()).isEqualByComparingTo("9");
    }

    @Test
    void submitUsesLatestLockedBookAndPreservesCrossingSale() {
        Head counting = head("COUNTING", false, 0, 101L, null, null);
        when(mapper.lockHead("TENANT_A", STOCKTAKE)).thenReturn(counting);
        when(mapper.countUncounted("TENANT_A", STOCKTAKE)).thenReturn(0);
        when(mapper.findLines("TENANT_A", STOCKTAKE)).thenReturn(List.of(line(new BigDecimal("10"),
            new BigDecimal("9"), null, 1)));
        when(inventoryMapper.lockBalance("TENANT_A", "dimension"))
            .thenReturn(new BalanceView("dimension", WAREHOUSE, 701L, "SALEABLE", new BigDecimal("8"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 7, 2));
        when(mapper.updateHeadStatus(any())).thenReturn(1);
        when(mapper.findHead("TENANT_A", STOCKTAKE)).thenReturn(head("PENDING_REVIEW", false, 1, 101L, null, null));

        service.submit(new Submit(STOCKTAKE, "trace-submit"));

        ArgumentCaptor<LineCutoffUpdate> cutoff = ArgumentCaptor.forClass(LineCutoffUpdate.class);
        verify(mapper).updateLineCutoff(cutoff.capture());
        assertThat(cutoff.getValue().adjustedBookQuantity()).isEqualByComparingTo("8");
        assertThat(cutoff.getValue().varianceQuantity()).isEqualByComparingTo("1");
        assertThat(cutoff.getValue().cutoffLedgerSequence()).isEqualTo(7);
    }

    @Test
    void zeroVariancePostsWithoutInventoryLedgerCommand() {
        Head reviewed = head("REVIEWED", false, 2, 101L, 202L, null);
        when(mapper.lockHead("TENANT_A", STOCKTAKE)).thenReturn(reviewed);
        when(mapper.findLines("TENANT_A", STOCKTAKE)).thenReturn(List.of(line(new BigDecimal("10"),
            new BigDecimal("10"), BigDecimal.ZERO, 1)));
        when(mapper.updateHeadStatus(any())).thenReturn(1);
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        when(mapper.findHead("TENANT_A", STOCKTAKE)).thenReturn(head("POSTED", false, 3, 101L, 202L, 303L));

        service.approve(new Approve(STOCKTAKE, EVENT, "trace-zero"));

        verify(movement, never()).applyOwnedMovement(any());
        verify(mapper, never()).insertAdjustment(any());
        verify(inventoryMapper).insertOutbox(any());
    }

    @Test
    void nonZeroVarianceUsesSingleStocktakeGainAndRejectsActorCollision() {
        Head reviewed = head("REVIEWED", false, 2, 101L, 202L, null);
        when(mapper.lockHead("TENANT_A", STOCKTAKE)).thenReturn(reviewed);
        when(mapper.findLines("TENANT_A", STOCKTAKE)).thenReturn(List.of(line(new BigDecimal("10"),
            new BigDecimal("11.5"), new BigDecimal("1.5"), 2)));
        when(mapper.updateHeadStatus(any())).thenReturn(1);
        when(movement.applyOwnedMovement(any())).thenReturn(new ApplyResult(EVENT, "STOCKTAKE",
            STOCKTAKE, 1, false, false));
        when(stores.businessDate(1101L, NOW)).thenReturn(new BusinessDateView(1101L, "Asia/Shanghai",
            LocalTime.of(6, 0), NOW, LocalDate.of(2026, 8, 17)));
        when(mapper.findHead("TENANT_A", STOCKTAKE)).thenReturn(head("POSTED", false, 3, 101L, 202L, 303L));

        service.approve(new Approve(STOCKTAKE, EVENT, "trace-gain"));

        ArgumentCaptor<OwnedMovement> captured = ArgumentCaptor.forClass(OwnedMovement.class);
        verify(movement).applyOwnedMovement(captured.capture());
        assertThat(captured.getValue().lines()).hasSize(1);
        assertThat(captured.getValue().lines().get(0).movementType()).isEqualTo(MovementType.STOCKTAKE_GAIN);
        assertThat(captured.getValue().lines().get(0).quantity()).isEqualByComparingTo("1.5");

        reset(movement);
        when(context.requirePrincipal()).thenReturn(new TrustedPrincipal("TENANT_A", 202L, 1L, "reviewer"));
        when(mapper.lockHead("TENANT_A", STOCKTAKE)).thenReturn(reviewed);
        assertThatThrownBy(() -> service.approve(new Approve(STOCKTAKE, EVENT, "trace-collision")))
            .isInstanceOf(ServiceException.class).hasMessageContaining("职责分离");
        verifyNoInteractions(movement);
    }

    private Head head(String status, boolean blind, long version, Long creator, Long reviewer, Long approver) {
        return new Head(STOCKTAKE, 1101L, WAREHOUSE, status, blind, BigDecimal.ONE,
            "POSTED".equals(status) ? EVENT : null, "trace", creator, reviewer, approver,
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), "COUNTING".equals(status) ? null : LocalDateTime.ofInstant(NOW, ZoneOffset.UTC),
            "POSTED".equals(status) ? LocalDateTime.ofInstant(NOW, ZoneOffset.UTC) : null, version);
    }

    private Line line(BigDecimal snapshot, BigDecimal counted, BigDecimal variance, int revision) {
        return new Line(LINE, STOCKTAKE, "dimension", WAREHOUSE, 701L, 301L, snapshot, 5,
            counted, variance == null ? null : counted.subtract(variance), variance == null ? 0 : 7,
            variance, revision, 102L);
    }
}
