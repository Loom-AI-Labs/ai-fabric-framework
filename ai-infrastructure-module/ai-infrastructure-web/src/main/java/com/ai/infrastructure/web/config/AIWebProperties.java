package com.ai.infrastructure.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.web")
public class AIWebProperties {
    private boolean enabled = true;
    private String basePath = "/api/ai";
    private Controllers controllers = new Controllers();
    
    @Data
    public static class Controllers {
        private boolean advancedRag = true;
        private boolean audit = true;
        private boolean compliance = true;
        private boolean monitoring = true;
        private boolean profile = true;
        private boolean security = true;
    }
}
