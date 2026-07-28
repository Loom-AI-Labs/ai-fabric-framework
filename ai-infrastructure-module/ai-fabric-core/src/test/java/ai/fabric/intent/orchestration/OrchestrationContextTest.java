package ai.fabric.intent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationContextTest {

    @Test
    void authenticatedContext_hasUserId() {
        OrchestrationContext context = OrchestrationContext.forUser("user-123");

        assertThat(context.isAuthenticated()).isTrue();
        assertThat(context.isAnonymous()).isFalse();
        assertThat(context.getIdentifier()).isEqualTo("user-123");
    }

    @Test
    void anonymousContext_hasSessionId() {
        OrchestrationContext context = OrchestrationContext.forSession("sess-456");

        assertThat(context.isAuthenticated()).isFalse();
        assertThat(context.isAnonymous()).isTrue();
        assertThat(context.getIdentifier()).isEqualTo("sess-456");
    }

    @Test
    void validate_requiresUserIdOrSessionId() {
        OrchestrationContext context = OrchestrationContext.builder().build();

        assertThatThrownBy(context::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userId")
            .hasMessageContaining("sessionId");
    }

    @Test
    void validate_passesWithUserOrSession() {
        assertThatCode(() -> OrchestrationContext.forUser("abc").validate()).doesNotThrowAnyException();
        assertThatCode(() -> OrchestrationContext.forSession("sess").validate()).doesNotThrowAnyException();
    }

    @Test
    void requestId_isGeneratedAndCached() {
        OrchestrationContext context = OrchestrationContext.forUser("user");

        String first = context.getOrGenerateRequestId();
        String second = context.getOrGenerateRequestId();

        assertThat(first).startsWith("rag-");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void builder_supportsRichFields() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user")
            .sessionId("sess")
            .requestId("req")
            .ipAddress("127.0.0.1")
            .userAgent("Mozilla/5.0")
            .locale(Locale.US)
            .metadata(Map.of("tier", "gold"))
            .build();

        assertThat(context.getUserId()).isEqualTo("user");
        assertThat(context.getSessionId()).isEqualTo("sess");
        assertThat(context.getRequestId()).isEqualTo("req");
        assertThat(context.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(context.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(context.getLocale()).isEqualTo(Locale.US);
        assertThat(context.getMetadata()).containsEntry("tier", "gold");
    }

    @Test
    void anonymousFactory_generatesSession() {
        OrchestrationContext context = OrchestrationContext.anonymous();

        assertThat(context.isAnonymous()).isTrue();
        assertThat(context.getSessionId()).startsWith("anon-");
    }

    @Test
    void specialistInstructionsAreNotSerialized() throws Exception {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user")
            .specialistInstructions("Application-only specialist contract")
            .build();

        String json = new ObjectMapper().writeValueAsString(context);

        assertThat(json)
            .doesNotContain(
                "specialistInstructions",
                "Application-only specialist contract"
            );
    }
}
