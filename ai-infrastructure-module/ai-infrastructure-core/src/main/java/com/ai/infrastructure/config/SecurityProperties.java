package com.ai.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for baseline security behaviours within the AI infrastructure module.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.security")
public class SecurityProperties {

    /**
     * When true, any detected PII within an incoming request will cause the security service
     * to block the request immediately. When false, the request is allowed to proceed so that
     * downstream sanitisation can handle masking.
     */
    private boolean blockOnPiiDetection = false;
}
