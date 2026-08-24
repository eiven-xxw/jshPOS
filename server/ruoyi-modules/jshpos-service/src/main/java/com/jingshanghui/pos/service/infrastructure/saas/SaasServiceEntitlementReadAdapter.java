package com.jingshanghui.pos.service.infrastructure.saas;

import com.jingshanghui.pos.saas.application.service.SaasEntitlementService;
import com.jingshanghui.pos.service.application.port.ServiceEntitlementReadPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SaaS 权益到 Service 只读端口的防腐适配器。
 *
 * <p>跨 Owner 类型只停留在该基础设施边界；Service 应用层只依赖自己的端口。</p>
 */
@Component
@RequiredArgsConstructor
public class SaasServiceEntitlementReadAdapter implements ServiceEntitlementReadPort {
    private final SaasEntitlementService entitlementService;

    @Override
    public AccessDecision decide(String featureCode) {
        var decision = entitlementService.decide(featureCode);
        return new AccessDecision(decision.allowed(), decision.reasonCode());
    }
}
