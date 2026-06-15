package ai.fabric.config.condition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * Enables search-related beans unless explicitly disabled via
 * {@code ai.service.features.enable-search=false}.
 */
public class SearchFeatureEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String raw = context.getEnvironment().getProperty("ai.service.features.enable-search");
        if (!StringUtils.hasText(raw)) {
            return true;
        }
        return Boolean.parseBoolean(raw.trim());
    }
}

