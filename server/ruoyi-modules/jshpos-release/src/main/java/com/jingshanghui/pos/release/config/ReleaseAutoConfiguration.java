package com.jingshanghui.pos.release.config;

import com.jingshanghui.pos.foundation.application.security.ScopeAuthorizationService;
import com.jingshanghui.pos.release.application.port.ReleasePorts.*;
import org.dromara.common.core.exception.ServiceException;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** Gate 6B 模块入口；真实对象验签或多Owner营业快照未接通时拒绝执行。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.release")
@MapperScan("com.jingshanghui.pos.release.infrastructure.persistence.mapper")
public class ReleaseAutoConfiguration {
    @Bean
    public AuthorizedStores releaseAuthorizedStores(ScopeAuthorizationService authorization) {
        return storeIds -> {
            authorization.requireTenantAdministrator();
            storeIds.forEach(authorization::requireStoreAccess);
        };
    }

    @Bean @ConditionalOnMissingBean(ArtifactVerifier.class)
    public ArtifactVerifier failClosedArtifactVerifier() {
        return release -> { throw new ServiceException("UPG-CFG-001: 受控对象存储与签名密钥注册表未配置", 503); };
    }

    @Bean @ConditionalOnMissingBean(SafetyProbe.class)
    public SafetyProbe failClosedSafetyProbe() {
        return (terminal, release) -> { throw new ServiceException("UPG-CFG-002: 多Owner营业保护探针未配置", 503); };
    }
}
