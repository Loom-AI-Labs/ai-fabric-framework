package com.ai.fabric.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.knowledge-sources.deployment")
public class RuntimeDeploymentKnowledgeSourceConfigProperties {

    /**
     * Optional classpath/file/http(s) location for deployment-managed knowledge source config.
     */
    private String configFile;
}
