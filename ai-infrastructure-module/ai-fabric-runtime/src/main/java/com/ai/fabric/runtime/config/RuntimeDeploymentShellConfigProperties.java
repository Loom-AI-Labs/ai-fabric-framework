package com.ai.fabric.runtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.shell.deployment")
public class RuntimeDeploymentShellConfigProperties {

    /**
     * Optional classpath/file/http(s) location for deployment-managed shell config.
     */
    private String configFile;
}
