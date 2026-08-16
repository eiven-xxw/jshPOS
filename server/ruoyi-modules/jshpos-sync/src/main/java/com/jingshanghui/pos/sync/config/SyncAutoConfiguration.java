package com.jingshanghui.pos.sync.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("com.jingshanghui.pos.sync")
@MapperScan("com.jingshanghui.pos.sync.infrastructure.persistence.mapper")
public class SyncAutoConfiguration {
}
