package com.jingshanghui.pos.order.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.order")
@MapperScan("com.jingshanghui.pos.order.infrastructure.persistence.mapper")
public class OrderAutoConfiguration {
}
