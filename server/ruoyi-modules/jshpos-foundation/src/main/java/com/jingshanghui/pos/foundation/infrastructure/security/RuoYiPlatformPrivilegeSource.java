package com.jingshanghui.pos.foundation.infrastructure.security;

import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

@Component
public class RuoYiPlatformPrivilegeSource implements PlatformPrivilegeSource {

    @Override
    public boolean isTenantAdministrator() {
        return LoginHelper.isTenantAdmin() || LoginHelper.isSuperAdmin();
    }

    @Override
    public boolean isPlatformAdministrator() {
        return LoginHelper.isSuperAdmin();
    }
}
