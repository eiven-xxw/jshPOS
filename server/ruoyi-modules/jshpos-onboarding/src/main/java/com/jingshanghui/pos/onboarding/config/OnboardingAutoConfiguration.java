package com.jingshanghui.pos.onboarding.config;

import com.jingshanghui.pos.onboarding.application.port.OnboardingOwnerGateway;
import com.jingshanghui.pos.onboarding.infrastructure.owner.FailClosedOnboardingOwnerGateway;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** T2-ONB-001 门店开通 Owner 自动装配；缺少 Owner 证据时默认失败关闭。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.onboarding")
@MapperScan("com.jingshanghui.pos.onboarding.infrastructure.persistence.mapper")
public class OnboardingAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(OnboardingOwnerGateway.class)
    public OnboardingOwnerGateway failClosedOnboardingOwnerGateway() {
        return new FailClosedOnboardingOwnerGateway();
    }
}
