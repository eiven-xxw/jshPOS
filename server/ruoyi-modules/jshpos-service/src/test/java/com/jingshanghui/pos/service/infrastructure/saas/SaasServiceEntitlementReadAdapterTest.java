package com.jingshanghui.pos.service.infrastructure.saas;

import com.jingshanghui.pos.saas.application.model.SaasModels.EntitlementDecision;
import com.jingshanghui.pos.saas.application.service.SaasEntitlementService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Service 与 SaaS 防腐适配器只映射只读授权结论，不泄漏配额写能力。 */
class SaasServiceEntitlementReadAdapterTest {

    @Test
    void shouldMapDeniedDecisionWithoutLeakingSaasModel() {
        SaasEntitlementService service = mock(SaasEntitlementService.class);
        when(service.decide("SERVICE_OPERATIONS")).thenReturn(new EntitlementDecision(
            false, "SUBSCRIPTION_ACCESS_DENIED", "version-1", 10L, 2L));

        var result = new SaasServiceEntitlementReadAdapter(service).decide("SERVICE_OPERATIONS");

        assertAll(
            () -> assertFalse(result.allowed()),
            () -> assertEquals("SUBSCRIPTION_ACCESS_DENIED", result.reason())
        );
    }
}
