package com.jingshanghui.pos.foundation.infrastructure.security;

/**
 * 平台角色能力适配端口。
 */
public interface PlatformPrivilegeSource {

    boolean isTenantAdministrator();

    /** 是否为平台管理员；该权限用于技术租户尚未创建前的商业开户。 */
    boolean isPlatformAdministrator();
}
