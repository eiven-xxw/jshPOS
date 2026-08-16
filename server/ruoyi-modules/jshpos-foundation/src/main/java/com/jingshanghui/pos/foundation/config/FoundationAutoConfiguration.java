package com.jingshanghui.pos.foundation.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Gate 0 模块入口。通过自动配置接入 RuoYi，避免修改上游系统模块的组件扫描边界。
 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.foundation")
@MapperScan("com.jingshanghui.pos.foundation.infrastructure.persistence.mapper")
public class FoundationAutoConfiguration {

    @Bean
    public Clock foundationClock() {
        return Clock.systemUTC();
    }
}
