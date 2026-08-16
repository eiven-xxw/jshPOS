package com.jingshanghui.pos.catalog.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 1 独立模块入口，不修改 RuoYi 系统模块扫描边界。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.catalog")
@MapperScan("com.jingshanghui.pos.catalog.infrastructure.persistence.mapper")
public class CatalogAutoConfiguration {
}
