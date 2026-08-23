package com.jingshanghui.pos.service.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** T2-SVC-001 模块装配，仅扫描 Service Owner 持久化 Mapper。 */
@Configuration
@MapperScan("com.jingshanghui.pos.service.infrastructure.persistence.mapper")
public class ServiceAutoConfiguration { }
