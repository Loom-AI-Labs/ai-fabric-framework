package ai.fabric.web.config;

import ai.fabric.web.controller.AIProfileController;
import ai.fabric.web.controller.AdvancedRAGController;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.service.AIInfrastructureProfileService;
import ai.fabric.service.VectorManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnProperty(prefix = "ai.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AIWebProperties.class)
public class AIWebAutoConfiguration {
    
    public AIWebAutoConfiguration() {
        log.info("AI Infrastructure Web AutoConfiguration initialized");
    }

    @Bean
    @ConditionalOnBean(AdvancedRAGService.class)
    @ConditionalOnMissingBean(AdvancedRAGController.class)
    @ConditionalOnProperty(prefix = "ai.web.controllers", name = "advanced-rag", havingValue = "true", matchIfMissing = true)
    public AdvancedRAGController advancedRAGController(AdvancedRAGService advancedRAGService,
                                                       ObjectProvider<VectorManagementService> vectorManagementService) {
        return new AdvancedRAGController(advancedRAGService, vectorManagementService.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean(AIInfrastructureProfileService.class)
    @ConditionalOnMissingBean(AIProfileController.class)
    @ConditionalOnProperty(prefix = "ai.web.controllers", name = "profile", havingValue = "true", matchIfMissing = true)
    public AIProfileController aiProfileController(AIInfrastructureProfileService profileService) {
        return new AIProfileController(profileService);
    }
}
