package com.jingshanghui.pos.migration.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** T2-DMT-001 Migration Owner 自动装配。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.migration")
@MapperScan("com.jingshanghui.pos.migration.infrastructure.persistence.mapper")
public class BusinessMigrationAutoConfiguration {
}
