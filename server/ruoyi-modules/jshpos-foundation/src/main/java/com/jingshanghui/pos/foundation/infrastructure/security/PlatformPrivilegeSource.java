package com.jingshanghui.pos.foundation.infrastructure.security;

/**
 * 平台角色能力适配端口。
 */
public interface PlatformPrivilegeSource {

    boolean isTenantAdministrator();
}
