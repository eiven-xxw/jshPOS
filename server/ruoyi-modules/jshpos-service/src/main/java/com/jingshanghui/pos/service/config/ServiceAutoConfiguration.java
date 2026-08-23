package com.jingshanghui.pos.service.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** T2-SVC-001 正式运行时自动装配入口；显式装配 Service Owner 组件与持久化 Mapper。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.service")
@MapperScan("com.jingshanghui.pos.service.infrastructure.persistence.mapper")
public class ServiceAutoConfiguration { }
