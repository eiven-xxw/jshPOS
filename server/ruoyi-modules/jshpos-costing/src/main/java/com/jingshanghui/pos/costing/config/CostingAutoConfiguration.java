package com.jingshanghui.pos.costing.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** Gate 4C 成本模块自动配置；缺失成本端口实现时库存模块不得以 No-op 启动。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.costing")
@MapperScan("com.jingshanghui.pos.costing.infrastructure.persistence.mapper")
public class CostingAutoConfiguration {
}
