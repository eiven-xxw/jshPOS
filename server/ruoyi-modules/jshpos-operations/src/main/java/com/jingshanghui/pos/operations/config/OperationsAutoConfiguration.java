package com.jingshanghui.pos.operations.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/** T2-CLS-001/T2-EXC-001 Operations Owner 自动装配。 */
@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.operations")
@MapperScan("com.jingshanghui.pos.operations.infrastructure.persistence.mapper")
public class OperationsAutoConfiguration {
}
