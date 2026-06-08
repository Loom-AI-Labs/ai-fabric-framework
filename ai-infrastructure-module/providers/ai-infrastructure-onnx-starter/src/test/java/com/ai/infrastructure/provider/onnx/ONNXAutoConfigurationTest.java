package com.ai.infrastructure.provider.onnx;

import com.ai.infrastructure.config.AIProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static org.assertj.core.api.Assertions.assertThat;

class ONNXAutoConfigurationTest {

    @Test
    void fallbackEmbeddingProviderIsExplicitOptIn() throws Exception {
        ConditionalOnProperty conditional = ONNXAutoConfiguration.class
            .getDeclaredMethod("onnxFallbackEmbeddingProvider", AIProviderConfig.class)
            .getAnnotation(ConditionalOnProperty.class);

        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("ai.providers.enable-fallback");
        assertThat(conditional.havingValue()).isEqualTo("true");
        assertThat(conditional.matchIfMissing()).isFalse();
    }
}
