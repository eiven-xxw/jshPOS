package com.jingshanghui.pos.resilience.application.service;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.resilience.application.port.StoreOnboardingBackupPort;
import com.jingshanghui.pos.resilience.infrastructure.persistence.mapper.StoreOnboardingBackupMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只返回已完成 PASS 的合成恢复演练摘要；不得将其升级为生产灾备证据。 */
@Service
@RequiredArgsConstructor
public class StoreOnboardingBackupService implements StoreOnboardingBackupPort {
    private final StoreOnboardingBackupMapper mapper;
    private final TrustedTenantContext tenantContext;
    private final ScopeAuthorizationService authorization;

    @Override
    @Transactional(readOnly = true)
    public BackupReadiness readiness() {
        authorization.requireTenantAdministrator();
        return mapper.findLatestPass(tenantContext.requireTenantId());
    }
}
