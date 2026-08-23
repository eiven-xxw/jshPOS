package com.jingshanghui.pos.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** T2-SVC-001 可执行 JAR 自动装配契约，防止模块只编译但 Controller 未注册。 */
class ServiceAutoConfigurationTest {

    @Test
    void shouldRegisterAsAutoConfigurationAndScanServiceOwnerComponents() {
        assertNotNull(ServiceAutoConfiguration.class.getAnnotation(AutoConfiguration.class));
        ComponentScan componentScan = ServiceAutoConfiguration.class.getAnnotation(ComponentScan.class);
        assertNotNull(componentScan);
        assertArrayEquals(new String[]{"com.jingshanghui.pos.service"}, componentScan.value());
    }
}
