package com.jingshanghui.pos.procurement.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.procurement.application.port.ProcurementCostSourcePort.ReceiptCostSource;
import com.jingshanghui.pos.procurement.infrastructure.persistence.mapper.ProcurementMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcurementCostSourceServiceTest {

    @Test
    void resolvesOnlyTenantScopedConfirmedFactsAndFailsClosedWhenMissing() {
        ProcurementMapper mapper = mock(ProcurementMapper.class);
        TrustedTenantContext context = mock(TrustedTenantContext.class);
        when(context.requireTenantId()).thenReturn("TENANT_A");
        String line = "01K2A000000000000000000001";
        ReceiptCostSource source = new ReceiptCostSource(line, "01K2A000000000000000000002",
            "01K2A000000000000000000003", 701L, 301L, BigDecimal.ONE, 100, 1, 1, "CNY");
        when(mapper.findConfirmedReceiptCostSource("TENANT_A", line)).thenReturn(source);
        ProcurementCostSourceService service = new ProcurementCostSourceService(mapper, context);

        assertThat(service.requireReceiptLine(line)).isSameAs(source);
        verify(mapper).findConfirmedReceiptCostSource("TENANT_A", line);
        assertThatThrownBy(() -> service.requireReturnLine("01K2A000000000000000000004"))
            .isInstanceOf(ServiceException.class).hasMessageContaining("CST-SOURCE-MISSING");
    }
}
