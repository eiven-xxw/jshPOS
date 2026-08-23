package com.jingshanghui.pos.saas.security;

import org.dromara.common.core.constant.SystemConstants;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SaaS 开户凭据的请求日志脱敏契约。
 */
class SensitiveRequestLoggingContractTest {

    @Test
    void excludesBootstrapPasswordFromRequestAndOperationLogs() {
        assertThat(Arrays.asList(SystemConstants.EXCLUDE_PROPERTIES))
            .contains("password", "bootstrapPassword");
    }
}
