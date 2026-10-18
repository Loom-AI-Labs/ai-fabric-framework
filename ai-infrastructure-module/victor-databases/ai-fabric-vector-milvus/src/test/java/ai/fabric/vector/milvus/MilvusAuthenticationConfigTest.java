package ai.fabric.vector.milvus;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.exception.AIServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;

class MilvusAuthenticationConfigTest {
    private static final String TOKEN = "fixture-serving-token-not-a-real-key";

    @Test
    void bindsTokenAndUsesNativeSdkAuthorizationWithoutPasswordEncoding() {
        var source = new MapConfigurationPropertySource(Map.of(
            "ai.providers.milvus.enabled", "true",
            "ai.providers.milvus.host", "vector.example.test",
            "ai.providers.milvus.port", "443",
            "ai.providers.milvus.secure", "true",
            "ai.providers.milvus.token", TOKEN));
        var config = new Binder(source).bind("ai.providers", Bindable.of(AIProviderConfig.class)).get();
        var connection = MilvusVectorDatabaseService.buildConnectParam(config.getMilvus());

        assertThat(connection.getToken()).isEqualTo(TOKEN);
        assertThat(new String(Base64.getDecoder().decode(connection.getAuthorization()), StandardCharsets.UTF_8))
            .isEqualTo(TOKEN);
        assertThat(connection.getUserName()).isEmpty();
        assertThat(connection.getHost()).isEqualTo("vector.example.test");
        assertThat(connection.getPort()).isEqualTo(443);
        assertThat(connection.isSecure()).isTrue();
        assertThat(connection.getDatabaseName()).isEqualTo("default");
        assertThat(config.toString()).doesNotContain(TOKEN);
        assertThat(config.getMilvus().toString()).doesNotContain(TOKEN);
    }

    @Test
    void preservesUsernamePasswordAndUnauthenticatedSelfManagedConfiguration() {
        var config = new AIProviderConfig.MilvusConfig();
        config.setUsername("fixture-user");
        config.setPassword("fixture-password");
        var connection = MilvusVectorDatabaseService.buildConnectParam(config);
        assertThat(connection.getToken()).isNull();
        assertThat(new String(Base64.getDecoder().decode(connection.getAuthorization()), StandardCharsets.UTF_8))
            .isEqualTo("fixture-user:fixture-password");
        assertThat(connection.getUserName()).isEqualTo("fixture-user");
        assertThat(connection.getPort()).isEqualTo(19530);
        assertThat(connection.isSecure()).isFalse();
        assertThat(MilvusVectorDatabaseService.buildConnectParam(new AIProviderConfig.MilvusConfig()).getToken()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", " token", "token ", "\u2003token", "token\u2003"})
    void rejectsExplicitBlankOrPaddedTokenWithoutDisclosingItsValue(String token) {
        var config = new AIProviderConfig.MilvusConfig();
        config.setToken(token);
        assertThatThrownBy(() -> MilvusVectorDatabaseService.buildConnectParam(config))
            .isInstanceOf(AIServiceException.class)
            .hasMessage("Milvus token must be nonblank and have no surrounding whitespace");
    }

    @Test
    void rejectsBothCredentialFormsBeforeClientConstruction() {
        for (boolean username : new boolean[] {true, false}) {
            var config = new AIProviderConfig.MilvusConfig();
            config.setToken(TOKEN);
            if (username) config.setUsername("fixture-user");
            else config.setPassword("fixture-password");
            assertThatThrownBy(() -> MilvusVectorDatabaseService.buildConnectParam(config))
                .isInstanceOf(AIServiceException.class)
                .hasMessage("Milvus token and username/password authentication cannot be combined")
                .hasMessageNotContaining(TOKEN);
        }
    }

    @Test
    void constructorRejectsMixedAuthenticationWithoutStartingAClient() {
        var config = new AIProviderConfig();
        config.getMilvus().setEnabled(true);
        config.getMilvus().setToken(TOKEN);
        config.getMilvus().setUsername("fixture-user");
        assertThatThrownBy(() -> new MilvusVectorDatabaseService(config))
            .isInstanceOf(AIServiceException.class)
            .hasMessage("Milvus token and username/password authentication cannot be combined")
            .hasMessageNotContaining(TOKEN);
    }

    @Test
    void sdkConstructorFailureCannotDiscloseTokenThroughTheExceptionChain() {
        var config = new AIProviderConfig();
        config.getMilvus().setEnabled(true);
        config.getMilvus().setToken(TOKEN);
        try (var ignored = mockConstruction(io.milvus.client.MilvusServiceClient.class,
            (client, context) -> { throw new IllegalStateException(TOKEN); })) {
            assertThatThrownBy(() -> new MilvusVectorDatabaseService(config))
                .isInstanceOf(AIServiceException.class)
                .hasMessageStartingWith("Failed to initialise Milvus client (")
                .hasMessageNotContaining(TOKEN)
                .hasNoCause();
        }
    }
}
