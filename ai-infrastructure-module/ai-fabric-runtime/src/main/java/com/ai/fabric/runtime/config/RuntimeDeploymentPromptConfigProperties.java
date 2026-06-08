package com.ai.fabric.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.prompts.deployment")
public class RuntimeDeploymentPromptConfigProperties {

    /**
     * Optional classpath/file/http(s) location for deployment-managed prompt config.
     */
    private String configFile;
}
