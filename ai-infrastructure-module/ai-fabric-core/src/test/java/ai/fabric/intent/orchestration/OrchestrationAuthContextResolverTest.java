package ai.fabric.intent.orchestration;

import ai.fabric.dto.AIAccessSubjectContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationAuthContextResolverTest {

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
