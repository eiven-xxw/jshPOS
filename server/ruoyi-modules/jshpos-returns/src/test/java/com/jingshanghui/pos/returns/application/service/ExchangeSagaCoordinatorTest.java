package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort;
import com.jingshanghui.pos.order.application.port.ExchangeOrderSnapshotPort.ExchangeOrderSnapshot;
import com.jingshanghui.pos.order.domain.UlidGenerator;
import com.jingshanghui.pos.returns.application.model.ExchangeCommands.OwnerObservation;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeLegView;
import com.jingshanghui.pos.returns.application.model.ExchangeViews.ExchangeView;
import com.jingshanghui.pos.returns.application.model.ReturnViews.ReturnView;
import com.jingshanghui.pos.returns.domain.ExchangeStates.Status;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EXG-001 固定故障向量：UNKNOWN 只观察原 Owner 命令，不生成替代退款或销售。 */
class ExchangeSagaCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-22T01:00:00Z");
    private static final String EXCHANGE = "01K5X000000000000000000001";
    private static final String RETURN = "01K5R000000000000000000001";
    private static final String RETURN_COMMAND = "01K5C000000000000000000001";
    private static final String ORIGINAL_ORDER = "01K5N000000000000000000001";
    private static final String NEW_ORDER = "01K5N000000000000000000002";
    private static final String NEW_SALE_COMMAND = "01K5C000000000000000000002";
    private static final String TERMINAL = "01K5T000000000000000000001";
    private static final String HASH = "a".repeat(64);

    private final ExchangeOrchestrationService exchanges = mock(ExchangeOrchestrationService.class);
    private final ReturnOrchestrationService returns = mock(ReturnOrchestrationService.class);
    private final ReturnSagaCoordinator returnCoordinator = mock(ReturnSagaCoordinator.class);
    private final ExchangeOrderSnapshotPort orders = mock(ExchangeOrderSnapshotPort.class);
    private final ExchangeSagaCoordinator coordinator = new ExchangeSagaCoordinator(
        exchanges, returns, returnCoordinator, orders,
        new UlidGenerator(Clock.fixed(NOW, ZoneOffset.UTC)), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void returnUnknownReusesOriginalReturnAggregateAndOnlyObservesIt() {
        ExchangeView current = view(Status.RETURN_UNKNOWN);
        ReturnView unknown = returnView("PAYMENT_UNKNOWN");
        when(exchanges.find(EXCHANGE)).thenReturn(current);
        when(returns.find(RETURN)).thenReturn(unknown);
        when(returnCoordinator.processNext(RETURN)).thenReturn(unknown);
        when(exchanges.acceptReturn(any())).thenReturn(current);

        coordinator.processNext(EXCHANGE);

        verify(returnCoordinator).processNext(RETURN);
        ArgumentCaptor<OwnerObservation> observation = ArgumentCaptor.forClass(OwnerObservation.class);
        verify(exchanges).acceptReturn(observation.capture());
        assertThat(observation.getValue().ownerAggregateId()).isEqualTo(RETURN);
        assertThat(observation.getValue().ownerStatus()).isEqualTo("PAYMENT_UNKNOWN");
        verify(orders, never()).find(any());
    }

    @Test
    void saleUnknownQueriesFrozenOrderAndDoesNotTouchReturnOwner() {
        ExchangeView current = view(Status.SALE_UNKNOWN);
        ExchangeOrderSnapshot unknown = new ExchangeOrderSnapshot(NEW_ORDER, 1101L, TERMINAL,
            LocalDate.parse("2026-08-22"), "COMPLETED", "UNKNOWN", "CNY",
            1280, HASH, "b".repeat(64), "c".repeat(64));
        when(exchanges.find(EXCHANGE)).thenReturn(current);
        when(orders.find(NEW_ORDER)).thenReturn(unknown);
        when(exchanges.acceptSale(any())).thenReturn(current);

        coordinator.processNext(EXCHANGE);

        verify(orders).find(NEW_ORDER);
        ArgumentCaptor<OwnerObservation> observation = ArgumentCaptor.forClass(OwnerObservation.class);
        verify(exchanges).acceptSale(observation.capture());
        assertThat(observation.getValue().ownerAggregateId()).isEqualTo(NEW_ORDER);
        assertThat(observation.getValue().ownerStatus()).isEqualTo("UNKNOWN");
        verify(returnCoordinator, never()).processNext(any());
    }

    @Test
    void terminalOrDraftCheckpointDoesNotInvokeAnyOwner() {
        for (Status status : List.of(Status.DRAFT, Status.COMPLETED, Status.FAILED, Status.CLOSED)) {
            ExchangeView current = view(status);
            when(exchanges.find(EXCHANGE)).thenReturn(current);
            assertThat(coordinator.processNext(EXCHANGE).status()).isEqualTo(status.name());
        }
        verify(returns, never()).find(any());
        verify(returnCoordinator, never()).processNext(any());
        verify(orders, never()).find(any());
        verify(exchanges, never()).acceptReturn(any());
        verify(exchanges, never()).acceptSale(any());
    }

    private ExchangeView view(Status status) {
        return new ExchangeView(EXCHANGE, RETURN, ORIGINAL_ORDER, RETURN_COMMAND, NEW_ORDER,
            NEW_SALE_COMMAND, 1101L, TERMINAL, LocalDate.parse("2026-08-22"), "CNY",
            900, status.ordinal() >= Status.RETURN_COMPLETED.ordinal() ? 900L : null,
            1280, status == Status.COMPLETED ? 1280L : null, 380, HASH, "b".repeat(64),
            status == Status.COMPLETED ? "c".repeat(64) : null, status.name(), 101L, 102L,
            "CUSTOMER_EXCHANGE", "01K5Z000000000000000000001", 3,
            List.of(new ExchangeLegView("01K5L000000000000000000001", "RETURN", "RETURN",
                    RETURN, RETURN_COMMAND, 900, HASH),
                new ExchangeLegView("01K5L000000000000000000002", "SALE", "ORDER",
                    NEW_ORDER, NEW_SALE_COMMAND, 1280, "b".repeat(64))),
            LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), false);
    }

    private ReturnView returnView(String status) {
        return new ReturnView(RETURN, RETURN_COMMAND, ORIGINAL_ORDER, 1101L, TERMINAL,
            "01K5H000000000000000000001", "01K5W000000000000000000001",
            LocalDate.parse("2026-08-22"), "PROVIDER_NEUTRAL",
            "01K5P000000000000000000001", null, "01K5S000000000000000000001", HASH,
            status, 1000L, 100L, 900L, null, "01K5E000000000000000000001", null,
            101L, 102L, "CUSTOMER_EXCHANGE", "01K5Z000000000000000000001", 3,
            List.of(), LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), false);
    }
}
