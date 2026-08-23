package com.jingshanghui.pos.foundation.infrastructure.security;

import org.dromara.common.core.constant.TenantConstants;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RuoYiPlatformPrivilegeSource implements PlatformPrivilegeSource {

    /**
     * 平台复核角色。RuoYi 只允许 user_id=1 成为内置超级管理员，因此商业开户的
     * 提交/审批职责分离必须使用受控平台角色，不能通过复制超级管理员账号实现。
     */
    static final String PLATFORM_ADMIN_ROLE_KEY = "platform_admin";

    @Override
    public boolean isTenantAdministrator() {
        return LoginHelper.isTenantAdmin() || LoginHelper.isSuperAdmin();
    }

    @Override
    public boolean isPlatformAdministrator() {
        LoginUser loginUser = LoginHelper.getLoginUser();
        Set<String> roleKeys = loginUser == null ? Set.of() : loginUser.getRolePermission();
        return isPlatformAdministrator(LoginHelper.getTenantId(), LoginHelper.isSuperAdmin(), roleKeys);
    }

    /**
     * 平台角色只能在默认平台租户生效；同名商户角色不得取得跨租户开户权限。
     */
    static boolean isPlatformAdministrator(String tenantId, boolean superAdmin, Set<String> roleKeys) {
        if (!TenantConstants.DEFAULT_TENANT_ID.equals(tenantId)) {
            return false;
        }
        return superAdmin || (roleKeys != null && roleKeys.contains(PLATFORM_ADMIN_ROLE_KEY));
    }
}
