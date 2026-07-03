package com.tonepilot.infrastructure.admin.data.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "tonepilot.persistence.enabled", havingValue = "true", matchIfMissing = true)
@MapperScan({
        "com.tonepilot.infrastructure.admin.data.mapper",
        "com.tonepilot.infrastructure.shared.persistence.mapper",
        "com.tonepilot.infrastructure.agent.workflow.mapper",
        "com.tonepilot.infrastructure.observability.repository.mapper",
        "com.tonepilot.infrastructure.runtime.repository.mapper",
        "com.tonepilot.infrastructure.knowledge.catalog.mapper"
})
public class AdminDataMybatisConfiguration {
}
