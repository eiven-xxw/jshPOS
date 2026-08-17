package com.jingshanghui.pos.returns.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 5B 原单退货退款编排模块自动配置。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.returns")
@MapperScan("com.jingshanghui.pos.returns.infrastructure.persistence.mapper")
public class ReturnsAutoConfiguration { }
