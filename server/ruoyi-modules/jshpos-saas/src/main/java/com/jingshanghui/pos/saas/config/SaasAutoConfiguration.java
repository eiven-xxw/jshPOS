package com.jingshanghui.pos.saas.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** T2-SAA-001 运行时自动装配入口。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.saas")
@MapperScan("com.jingshanghui.pos.saas.infrastructure.persistence.mapper")
public class SaasAutoConfiguration { }
