package com.jingshanghui.pos.transfer.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.DispatchCostSource;
import com.jingshanghui.pos.transfer.application.port.TransferCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.transfer.infrastructure.persistence.mapper.TransferMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransferCostSourceServiceTest {
    private static final String LINE = "01K2A000000000000000000001";

    @Test
    void resolvesOnlyTrustedTenantPostedFacts() {
        TransferMapper mapper = mock(TransferMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        DispatchCostSource dispatch = new DispatchCostSource(LINE, "D", "T", "W1", "W2",
            1L, 2L, BigDecimal.ONE, "CNY");
        ReceiptCostSource receipt = new ReceiptCostSource(LINE, "R", "D1", "W1", "W2",
            1L, 2L, BigDecimal.ONE, "CNY");
        when(mapper.findPostedDispatchCostSource("TENANT_A", LINE)).thenReturn(dispatch);
        when(mapper.findPostedReceiptCostSource("TENANT_A", LINE)).thenReturn(receipt);
        TransferCostSourceService service = new TransferCostSourceService(mapper, context);

        assertThat(service.requireDispatchLine(LINE)).isEqualTo(dispatch);
        assertThat(service.requireReceiptLine(LINE)).isEqualTo(receipt);
        verify(mapper).findPostedDispatchCostSource("TENANT_A", LINE);
        verify(mapper).findPostedReceiptCostSource("TENANT_A", LINE);
    }

    @Test
    void failsClosedWhenFactIsMissing() {
        TransferMapper mapper = mock(TransferMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        TransferCostSourceService service = new TransferCostSourceService(mapper, context);
        assertThatThrownBy(() -> service.requireDispatchLine(LINE)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.requireReceiptLine(LINE)).isInstanceOf(ServiceException.class);
    }
}
