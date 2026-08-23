package com.jingshanghui.pos.subscription.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** T2-SUB-001 正式运行时自动装配入口。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.subscription")
@MapperScan("com.jingshanghui.pos.subscription.infrastructure.persistence.mapper")
public class SubscriptionAutoConfiguration { }
