package com.jingshanghui.pos.saas.security;

import cn.dev33.satoken.util.SaFoxUtil;
import org.dromara.common.core.constant.SystemConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台超级管理员对 RuoYi 与鲸熵汇两类权限编码的兼容契约。
 */
class SuperAdminPermissionPatternContractTest {

    @Test
    void coversBothThreeSegmentAndTwoSegmentPermissions() {
        assertThat(SaFoxUtil.vagueMatch(SystemConstants.ALL_PERMISSION, "system:user:list")).isTrue();
        assertThat(SaFoxUtil.vagueMatch(SystemConstants.DOMAIN_ALL_PERMISSION, "subscription:create")).isTrue();
        assertThat(SaFoxUtil.vagueMatch(SystemConstants.ALL_PERMISSION, "subscription:create")).isFalse();
    }
}
