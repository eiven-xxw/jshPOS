package com.jingshanghui.pos.inventory.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 4A 库存模块自动配置。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.inventory")
@MapperScan("com.jingshanghui.pos.inventory.infrastructure.persistence.mapper")
public class InventoryAutoConfiguration {
}
