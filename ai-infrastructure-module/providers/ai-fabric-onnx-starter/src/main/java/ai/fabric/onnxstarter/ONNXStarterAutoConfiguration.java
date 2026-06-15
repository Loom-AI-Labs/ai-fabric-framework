package ai.fabric.onnxstarter;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.provider.onnx.ONNXEmbeddingProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Auto-configuration entry point for the optional ONNX starter.
 * <p>
 * When this module is on the classpath it ensures the core AI infrastructure
 * auto-configuration is loaded and logs the availability of ONNX runtime defaults.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@ConditionalOnClass(ONNXEmbeddingProvider.class)
@EnableConfigurationProperties
public class ONNXStarterAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ONNXStarterAutoConfiguration.class);

    @PostConstruct
    void logStarterActivation() {
        log.info("AI Infrastructure ONNX starter detected. ONNX runtime defaults are active.");
    }
}
