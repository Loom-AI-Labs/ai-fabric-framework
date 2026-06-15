package ai.fabric.intent.action;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ActionContextTest {

    @Test
    void authContextUsesUserIdWhenMetadataDoesNotProvideSubjectId() {
        OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123");

        AIAccessSubjectContext authContext = new ActionContext(orchestrationContext, null).authContext();

        assertThat(authContext.getSubjectId()).isEqualTo("user-123");
        assertThat(authContext.getSubjectType()).isEqualTo("END_USER");
    }

    @Test
    void authContextPrefersExplicitMetadataSubjectId() {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-123")
            .sessionId("session-456")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.SUBJECT_ID, "subject-789",
                OrchestrationContextMetadataKeys.SUBJECT_TYPE, "SERVICE_ACCOUNT"
            ))
            .build();

        AIAccessSubjectContext authContext = new ActionContext(orchestrationContext, null).authContext();

        assertThat(authContext.getSubjectId()).isEqualTo("subject-789");
        assertThat(authContext.getSubjectType()).isEqualTo("SERVICE_ACCOUNT");
    }
}
