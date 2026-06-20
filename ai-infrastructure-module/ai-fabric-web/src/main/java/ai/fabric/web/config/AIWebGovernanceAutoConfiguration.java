package ai.fabric.web.config;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.security.AISecurityService;
import ai.fabric.web.controller.AIComplianceController;
import ai.fabric.web.controller.AISecurityController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@AutoConfigureAfter(AIWebAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnClass(name = {
    "org.springframework.web.servlet.DispatcherServlet",
    "ai.fabric.compliance.AIComplianceService",
    "ai.fabric.security.AISecurityService"
})
@ConditionalOnProperty(prefix = "ai.web", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AIWebGovernanceAutoConfiguration {

    @Bean
    @ConditionalOnBean(AIComplianceService.class)
    @ConditionalOnMissingBean(AIComplianceController.class)
    @ConditionalOnProperty(prefix = "ai.web.controllers", name = "compliance", havingValue = "true", matchIfMissing = true)
    public AIComplianceController aiComplianceController(AIComplianceService complianceService) {
        return new AIComplianceController(complianceService);
    }

    @Bean
    @ConditionalOnBean(AISecurityService.class)
    @ConditionalOnMissingBean(AISecurityController.class)
    @ConditionalOnProperty(prefix = "ai.web.controllers", name = "security", havingValue = "true", matchIfMissing = true)
    public AISecurityController aiSecurityController(AISecurityService securityService) {
        return new AISecurityController(securityService);
    }
}
