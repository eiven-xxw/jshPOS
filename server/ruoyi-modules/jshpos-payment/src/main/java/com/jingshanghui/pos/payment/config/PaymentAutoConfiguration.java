package com.jingshanghui.pos.payment.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 3A 支付模块自动配置，只装配本地领域和持久化能力。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.payment")
@MapperScan("com.jingshanghui.pos.payment.infrastructure.persistence.mapper")
public class PaymentAutoConfiguration {
}
