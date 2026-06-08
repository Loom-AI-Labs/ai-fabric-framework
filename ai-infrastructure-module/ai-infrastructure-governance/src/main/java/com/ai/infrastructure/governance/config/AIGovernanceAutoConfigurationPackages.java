package com.ai.infrastructure.governance.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@AutoConfigurationPackage(basePackages = "com.ai.infrastructure.governance")
@ConditionalOnProperty(prefix = "ai.governance", name = "enabled", havingValue = "true")
public class AIGovernanceAutoConfigurationPackages {}
