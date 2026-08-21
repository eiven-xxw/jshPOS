package com.jingshanghui.pos.order.application.service;

import com.jingshanghui.pos.order.infrastructure.persistence.mapper.OrderMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderFinalityGuardServiceTest {

    private static final String ORDER = "01K2A000000000000000000051";
    private static final String SOURCE = "01K2A000000000000000000071";
    private static final String HASH = "a".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 3, 0);

    private final OrderMapper mapper = mock(OrderMapper.class);
    private final OrderFinalityGuardService service = new OrderFinalityGuardService(mapper);

    @Test
    void cancellationAndCompletionReserveTheSameTenantOrderKey() {
        service.reserveCancellation("TENANT_A", ORDER, SOURCE, HASH, NOW);

        verify(mapper).insertOrderFinalityGuard(
            "TENANT_A", ORDER, "CANCELLED", SOURCE, HASH, NOW);
    }

    @Test
    void historicalCancellationAndConcurrentUniqueConflictFailClosed() {
        when(mapper.countCancellationDisposition("TENANT_A", ORDER)).thenReturn(1);
        assertThatThrownBy(() -> service.reserveCompletion(
            "TENANT_A", ORDER, SOURCE, HASH, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_CANCELLATION_BLOCKED");

        when(mapper.countCancellationDisposition("TENANT_A", ORDER)).thenReturn(0);
        doThrow(new DuplicateKeyException("synthetic competing finality"))
            .when(mapper).insertOrderFinalityGuard(
                "TENANT_A", ORDER, "COMPLETED", SOURCE, HASH, NOW);
        assertThatThrownBy(() -> service.reserveCompletion(
            "TENANT_A", ORDER, SOURCE, HASH, NOW))
            .isInstanceOf(ServiceException.class).hasMessageContaining("ORDER_FINALITY_CONFLICT");
    }
}
