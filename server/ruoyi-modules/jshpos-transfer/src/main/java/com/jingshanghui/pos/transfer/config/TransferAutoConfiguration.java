package com.jingshanghui.pos.transfer.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 4D 调拨模块自动配置。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.transfer")
@MapperScan("com.jingshanghui.pos.transfer.infrastructure.persistence.mapper")
public class TransferAutoConfiguration {
}
