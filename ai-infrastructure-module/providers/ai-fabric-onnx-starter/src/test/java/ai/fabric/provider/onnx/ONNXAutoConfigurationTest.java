package ai.fabric.provider.onnx;

import ai.fabric.config.AIProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

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

    @Test
    void resolvePathUsesEmptyOptionalForUnresolvableInputs() throws Exception {
        ONNXEmbeddingProvider provider = new ONNXEmbeddingProvider(new AIProviderConfig());
        Method resolvePath = ONNXEmbeddingProvider.class
            .getDeclaredMethod("resolvePath", String.class, String.class);
        resolvePath.setAccessible(true);

        assertThat(resolvePath(resolvePath, provider, "", "model")).isEmpty();
        assertThat(resolvePath(resolvePath, provider, "classpath:/missing/model.onnx", "model")).isEmpty();
        assertThat(resolvePath(resolvePath, provider, "\0bad", "model")).isEmpty();
    }

    @Test
    void resolvePathNormalizesRelativePathsAgainstWorkingDirectory() throws Exception {
        ONNXEmbeddingProvider provider = new ONNXEmbeddingProvider(new AIProviderConfig());
        Method resolvePath = ONNXEmbeddingProvider.class
            .getDeclaredMethod("resolvePath", String.class, String.class);
        resolvePath.setAccessible(true);

        Optional<Path> resolved = resolvePath(resolvePath, provider, "models/example.onnx", "model");

        assertThat(resolved).contains(Paths.get(System.getProperty("user.dir"))
            .resolve("models/example.onnx")
            .normalize());
    }

    @Test
    void findLongestSubwordUsesEmptyStringWhenNoCandidateExists() throws Exception {
        ONNXEmbeddingProvider provider = new ONNXEmbeddingProvider(new AIProviderConfig());
        Method findLongestSubword = ONNXEmbeddingProvider.class
            .getDeclaredMethod("findLongestSubword", String.class);
        findLongestSubword.setAccessible(true);

        assertThat((String) findLongestSubword.invoke(provider, "")).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private Optional<Path> resolvePath(Method method,
                                       ONNXEmbeddingProvider provider,
                                       String configuredPath,
                                       String descriptor) throws Exception {
        return (Optional<Path>) method.invoke(provider, configuredPath, descriptor);
    }
}
