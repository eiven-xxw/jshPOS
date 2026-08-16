package com.jingshanghui.pos.foundation.application.context;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Gate 0 唯一可信租户上下文入口。所有缺失或畸形身份均失败关闭。
 */
@Service
@RequiredArgsConstructor
public class TrustedTenantContext {

    private static final Pattern TENANT_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$");
    private static final int ERROR_UNAUTHORIZED = 401;

    private final TrustedPrincipalSource principalSource;

    public TrustedPrincipal requirePrincipal() {
        TrustedPrincipal principal = principalSource.current()
            .orElseThrow(() -> new ServiceException("FND-IAM-001: 缺少可信认证上下文", ERROR_UNAUTHORIZED));
        if (principal.tenantId() == null || !TENANT_ID.matcher(principal.tenantId()).matches()) {
            throw new ServiceException("FND-IAM-002: 可信租户上下文无效", ERROR_UNAUTHORIZED);
        }
        if (principal.userId() == null || principal.userId() <= 0) {
            throw new ServiceException("FND-IAM-003: 可信操作者上下文无效", ERROR_UNAUTHORIZED);
        }
        MDC.put("tenantId", principal.tenantId());
        return principal;
    }

    public String requireTenantId() {
        return requirePrincipal().tenantId();
    }

    public void rejectClientTenant(String clientTenantId) {
        if (clientTenantId != null && !clientTenantId.isBlank()) {
            throw new ServiceException("FND-IAM-004: 客户端租户标识不得参与授权", 400);
        }
    }
}
