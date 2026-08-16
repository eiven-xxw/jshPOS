package com.jingshanghui.pos.procurement.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 4B 采购模块自动配置。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.procurement")
@MapperScan("com.jingshanghui.pos.procurement.infrastructure.persistence.mapper")
public class ProcurementAutoConfiguration {
}
