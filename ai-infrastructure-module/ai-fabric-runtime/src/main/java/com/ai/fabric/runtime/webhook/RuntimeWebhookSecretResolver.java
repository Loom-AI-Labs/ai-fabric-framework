package com.ai.fabric.runtime.webhook;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RuntimeWebhookSecretResolver {

    private final Environment environment;

    public RuntimeWebhookSecretResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolveRequired(String secretRef) {
        if (!StringUtils.hasText(secretRef)) {
            throw new IllegalArgumentException("Webhook secret ref is required.");
        }
        String value = environment != null ? environment.getProperty(secretRef.trim()) : null;
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Webhook secret '" + secretRef.trim() + "' is not configured in the runtime environment.");
        }
        return value.trim();
    }
}
