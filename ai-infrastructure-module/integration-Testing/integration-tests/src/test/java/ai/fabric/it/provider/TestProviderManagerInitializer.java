package ai.fabric.it.provider;

import ai.fabric.provider.AIProviderManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Ensures {@link AIProviderManager} has registered providers in integration tests.
 */
@Component
@RequiredArgsConstructor
public class TestProviderManagerInitializer {

    private static final Logger log = LoggerFactory.getLogger(TestProviderManagerInitializer.class);

    private final AIProviderManager providerManager;

    @PostConstruct
    void initProviderManager() {
        log.info("Initializing AIProviderManager for integration tests");
        providerManager.initialize();
    }
}
