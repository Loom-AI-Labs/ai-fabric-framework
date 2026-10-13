package ai.fabric.chat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Opt-in persistence settings for generic conversation control. */
@ConfigurationProperties(prefix = "ai.chat.control")
public class ConversationControlProperties {

    private boolean enabled;
    private boolean initializeSchema;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean initializeSchema) {
        this.initializeSchema = initializeSchema;
    }
}
