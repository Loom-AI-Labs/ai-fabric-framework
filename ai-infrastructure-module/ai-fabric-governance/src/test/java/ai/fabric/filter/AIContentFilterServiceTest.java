package ai.fabric.filter;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIContentFilterRequest;
import ai.fabric.governance.config.AIGovernanceProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIContentFilterServiceTest {

    @Test
    void usesTypedPrimaryDecisionAndCanonicalSubjectId() {
        ContentModerationProvider primary = provider("primary", request -> new ContentModerationResult(
            false,
            List.of(),
            request.getContent(),
            0.02,
            List.of("ALLOW"),
            Map.of("model", "moderation-v1")
        ));
        AIContentFilterService service = new AIContentFilterService(List.of(primary), properties(null, List.of()));

        var response = service.filterContent(request("filter-1", "safe content"));

        assertThat(response.getSubjectId()).isEqualTo("customer-7");
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getShouldFilter()).isFalse();
        assertThat(response.getSanitizedContent()).isEqualTo("safe content");
        assertThat(response.getContentScore()).isEqualTo(0.02);
        assertThat(response.getContentAnalysis())
            .containsEntry("decision", "CONTENT_MODERATION")
            .containsEntry("primaryProvider", "primary")
            .containsEntry("effectiveProvider", "primary")
            .containsEntry("recoveryAttempted", false)
            .containsEntry("success", true);
    }

    @Test
    void usesOnlyExplicitlyNamedRecoveryProviderAfterPrimaryFailure() {
        ContentModerationProvider primary = provider("primary", request -> {
            throw new IllegalStateException("provider unavailable");
        });
        ContentModerationProvider recovery = provider("recovery", request -> new ContentModerationResult(
            true,
            List.of("HARASSMENT"),
            null,
            0.93,
            List.of("BLOCK"),
            Map.of()
        ));
        ContentModerationProvider unconfigured = provider("unconfigured", request -> {
            throw new AssertionError("unconfigured provider must not be called");
        });
        AIContentFilterService service = new AIContentFilterService(
            List.of(primary, recovery, unconfigured),
            properties("primary", List.of("recovery"))
        );

        var response = service.filterContent(request("filter-2", "content"));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getShouldFilter()).isTrue();
        assertThat(response.getViolations()).containsExactly("HARASSMENT");
        assertThat(response.getContentAnalysis())
            .containsEntry("primaryProvider", "primary")
            .containsEntry("configuredRecoveryProviders", List.of("recovery"))
            .containsEntry("effectiveProvider", "recovery")
            .containsEntry("recoveryAttempted", true);
        assertThat((List<?>) response.getContentAnalysis().get("attempts")).hasSize(2);
    }

    @Test
    void failsClosedWhenConfiguredProviderChainIsExhausted() {
        ContentModerationProvider primary = provider("primary", request -> null);
        AIContentFilterService service = new AIContentFilterService(
            List.of(primary),
            properties("primary", List.of())
        );

        var response = service.filterContent(request("filter-3", "content"));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getShouldFilter()).isTrue();
        assertThat(response.getViolations()).containsExactly("MODERATION_PROVIDER_ERROR");
        assertThat(response.getErrorMessage()).contains("No configured moderation provider");
        assertThat(response.getContentAnalysis())
            .containsEntry("primaryProvider", "primary")
            .containsEntry("recoveryAttempted", false)
            .containsEntry("success", false)
            .doesNotContainKey("effectiveProvider");
    }

    @Test
    void rejectsMissingOrAmbiguousPrimaryProviderConfiguration() {
        assertThatThrownBy(() -> new AIContentFilterService(List.of(), properties(null, List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary-provider is required");

        assertThatThrownBy(() -> new AIContentFilterService(
            List.of(provider("one", request -> null), provider("two", request -> null)),
            properties(null, List.of())
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary-provider is required");
    }

    @Test
    void rejectsUnknownConfiguredProviderIds() {
        ContentModerationProvider primary = provider("primary", request -> null);

        assertThatThrownBy(() -> new AIContentFilterService(
            List.of(primary),
            properties("missing", List.of())
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("primary moderation provider is not registered: missing");

        assertThatThrownBy(() -> new AIContentFilterService(
            List.of(primary),
            properties("primary", List.of("missing"))
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recovery provider is not registered: missing");
    }

    @Test
    void rejectsNullRequest() {
        AIContentFilterService service = new AIContentFilterService(
            List.of(provider("primary", request -> null)),
            properties(null, List.of())
        );

        assertThatThrownBy(() -> service.filterContent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("content filter request");
    }

    private static ContentModerationProvider provider(
        String id,
        Function<AIContentFilterRequest, ContentModerationResult> moderation
    ) {
        return new ContentModerationProvider() {
            @Override
            public String providerId() {
                return id;
            }

            @Override
            public ContentModerationResult moderate(AIContentFilterRequest request) {
                return moderation.apply(request);
            }
        };
    }

    private static AIGovernanceProperties.ContentFilterProperties properties(
        String primaryProvider,
        List<String> recoveryProviders
    ) {
        AIGovernanceProperties.ContentFilterProperties properties =
            new AIGovernanceProperties.ContentFilterProperties();
        properties.setPrimaryProvider(primaryProvider);
        properties.setRecoveryProviders(recoveryProviders);
        return properties;
    }

    private static AIContentFilterRequest request(String requestId, String content) {
        return AIContentFilterRequest.builder()
            .requestId(requestId)
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("customer-7")
                .sessionId("session-7")
                .subjectType("END_USER")
                .build())
            .content(content)
            .build();
    }
}
