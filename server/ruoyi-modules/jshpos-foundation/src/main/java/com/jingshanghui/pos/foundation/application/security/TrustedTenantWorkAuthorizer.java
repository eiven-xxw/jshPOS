package com.jingshanghui.pos.foundation.application.security;

import com.jingshanghui.pos.foundation.application.context.TrustedTenantContext;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

/**
 * Gate 0 任务只能沿用受控调度器已建立的上下文；普通任务参数不能切换租户。
 */
@Service
@RequiredArgsConstructor
public class TrustedTenantWorkAuthorizer {

    private final TrustedTenantContext tenantContext;

    public void requireCurrentTenant(String untrustedRequestedTenant) {
        String currentTenant = tenantContext.requireTenantId();
        if (untrustedRequestedTenant != null && !currentTenant.equals(untrustedRequestedTenant)) {
            throw new ServiceException("FND-IAM-006: 任务租户与可信上下文不一致", 403);
        }
    }
}
