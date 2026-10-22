package ai.fabric.intent.orchestration;

import ai.fabric.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationAuthContextResolverTest {

    @Test
    void interactiveCallerIsNotTheConversationBeingProcessed() {
        var trusted = new ai.fabric.execution.context.TrustedExecutionContext(
            new ai.fabric.execution.context.ExecutionPrincipal("verified-user", ai.fabric.execution.context.ExecutionPrincipalType.END_USER),
            new ai.fabric.execution.context.ExecutionSubjectRef("conversation", "conversation-123"),
            ai.fabric.execution.context.ExecutionSource.INTERACTIVE,
            "tenant-1", "deployment-1", java.util.Set.of("chat:query"), "request-1", null
        );
        var auth = OrchestrationAuthContextResolver.from(trusted);
        assertThat(auth.getSubjectId()).isEqualTo("verified-user");
        assertThat(auth.getSubjectType()).isEqualTo("END_USER");
    }

    @Test
    void verifiedCallerFactsSurviveWithoutReadingLegacyMetadataOrExpandingScopes() {
        var trusted = new ai.fabric.execution.context.TrustedExecutionContext(
            new ai.fabric.execution.context.ExecutionPrincipal("verified-user", ai.fabric.execution.context.ExecutionPrincipalType.END_USER),
            new ai.fabric.execution.context.ExecutionSubjectRef("conversation", "conversation-123"),
            ai.fabric.execution.context.ExecutionSource.INTERACTIVE,
            "tenant-1", "deployment-1", java.util.Set.of("chat:query"), "request-1", null
        ).withCallerIdentity(new ai.fabric.execution.context.ExecutionCallerIdentity(
            "END_USER", "PUBLIC_RUNTIME_TOKEN", "PUBLIC_BROWSER", "verified-session", "customer-1",
            "verified-issuer", java.util.List.of("runtime"), java.time.Instant.parse("2026-10-23T12:00:00Z")));
        var auth = OrchestrationAuthContextResolver.from(trusted);
        assertThat(auth.getSubjectId()).isEqualTo("verified-user");
        assertThat(auth.getSubjectType()).isEqualTo("END_USER");
        assertThat(auth.getSessionId()).isEqualTo("verified-session");
        assertThat(auth.getCustomerId()).isEqualTo("customer-1");
        assertThat(auth.getTenantId()).isEqualTo("tenant-1");
        assertThat(auth.getDeploymentId()).isEqualTo("deployment-1");
        assertThat(auth.getAuthMode()).isEqualTo("PUBLIC_RUNTIME_TOKEN");
        assertThat(auth.getCallerType()).isEqualTo("PUBLIC_BROWSER");
        assertThat(auth.getIssuer()).isEqualTo("verified-issuer");
        assertThat(auth.getExpiresAt()).isEqualTo("2026-10-23T12:00:00Z");
        assertThat(auth.getAudiences()).containsExactly("runtime");
        assertThat(auth.getGrantedScopes()).containsExactly("chat:query");
    }

    @Test
    void shouldUseUserIdWhenMetadataDoesNotProvideSubjectId() {
        OrchestrationContext context = OrchestrationContext.forUser("user-123");

        AIAccessSubjectContext authContext = OrchestrationAuthContextResolver.from(context);

        assertThat(authContext.getSubjectId()).isEqualTo("user-123");
        assertThat(authContext.getSubjectType()).isEqualTo("END_USER");
        assertThat(authContext.getSessionId()).isNull();
    }

    @Test
    void shouldPreferMetadataSubjectIdWhenPresent() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-123")
            .metadata(java.util.Map.of(
                OrchestrationContextMetadataKeys.SUBJECT_ID, "subject-456",
                OrchestrationContextMetadataKeys.SUBJECT_TYPE, "SERVICE_ACCOUNT"
            ))
            .build();

        AIAccessSubjectContext authContext = OrchestrationAuthContextResolver.from(context);

        assertThat(authContext.getSubjectId()).isEqualTo("subject-456");
        assertThat(authContext.getSubjectType()).isEqualTo("SERVICE_ACCOUNT");
    }
}
