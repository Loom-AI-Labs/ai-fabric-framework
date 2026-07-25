package ai.fabric.relationship.support;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Clock;
import java.util.List;

public final class RelationshipProjectionTestSupport {

    private RelationshipProjectionTestSupport() {
    }

    public static AIEntityProjectionService projectionService(
        AIEntityConfigurationLoader configurationLoader
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectProvider<PIIDetectionService> piiProvider =
            new StaticListableBeanFactory()
                .getBeanProvider(PIIDetectionService.class);
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            configurationLoader,
            List.of(),
            List.of(),
            piiProvider,
            objectMapper
        );
        return new AIEntityProjectionService(
            registry,
            piiProvider,
            objectMapper,
            Clock.systemUTC()
        );
    }
}
