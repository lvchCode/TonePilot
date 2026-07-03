package com.tonepilot.infrastructure.admin.data.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tonepilot.persistence.enabled", havingValue = "true", matchIfMissing = true)
@MapperScan("com.tonepilot.infrastructure.admin.data.mapper")
public class AdminDataMybatisConfiguration {
}
