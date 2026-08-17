package com.jingshanghui.pos.sync.config;

import com.jingshanghui.pos.sync.domain.TerminalSecretProtector;
import com.jingshanghui.pos.sync.infrastructure.security.HmacTerminalSecretProtector;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Gate 6A 终端秘密配置；缺少外部 pepper 时应用可启动，但所有秘密操作失败关闭。 */
@Configuration(proxyBeanMethods = false)
public class TerminalSecurityConfiguration {
    @Bean
    TerminalSecretProtector terminalSecretProtector(Environment environment) {
        String pepper = environment.getProperty("JSH_TERMINAL_ACTIVATION_PEPPER");
        if (pepper == null || pepper.length() < 32) {
            return (purpose, secret) -> {
                throw new ServiceException("TRM_SECRET_UNAVAILABLE: 终端激活 pepper 未配置", 503);
            };
        }
        return new HmacTerminalSecretProtector(pepper);
    }
}
