package ai.fabric.execution.specialist.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestCompiler;
import ai.fabric.execution.specialist.manifest.ManifestTestFixtures;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultSpecialistClientFactoryTest {

    @Test
    void bindsCompatibleDtosAndConvertsThroughJsonGateway() {
        var compiled = new DefaultSpecialistManifestCompiler()
            .compile(
                ManifestTestFixtures.manifest(),
                ManifestTestFixtures.compilationContext()
            )
            .specialist();
        var registry = new DefaultSpecialistRegistry(
            List.of(compiled),
            ManifestTestFixtures.definitionValidator()
        );
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        ObjectNode rawOutput = ManifestTestFixtures.objectMapper()
            .createObjectNode()
            .put("answer", "Use the approved recovery process.");
        when(gateway.execute(any())).thenReturn(new AIExecutionResult<>(
            "exec-1",
            SpecialistId.of("support-knowledge", "1"),
            AIExecutionStatus.SUCCEEDED,
            rawOutput,
            List.of(),
            Map.of(),
            null,
            Instant.EPOCH,
            Instant.EPOCH
        ));
        SpecialistClientFactory factory = new DefaultSpecialistClientFactory(
            registry,
            gateway,
            ManifestTestFixtures.objectMapper()
        );

        SpecialistClient<Question, Answer> client = factory.bind(
            SpecialistId.of("support-knowledge", "1"),
            Question.class,
            Answer.class
        );
        AIExecutionResult<Answer> result = client.execute(
            new Question("How do I reset MFA?"),
            trustedContext()
        );

        assertThat(result.output().answer())
            .isEqualTo("Use the approved recovery process.");
    }

    @Test
    void rejectsJavaTypeThatDoesNotMatchPinnedSchemaAtBindingTime() {
        var compiled = new DefaultSpecialistManifestCompiler()
            .compile(
                ManifestTestFixtures.manifest(),
                ManifestTestFixtures.compilationContext()
            )
            .specialist();
        SpecialistClientFactory factory = new DefaultSpecialistClientFactory(
            new DefaultSpecialistRegistry(
                List.of(compiled),
                ManifestTestFixtures.definitionValidator()
            ),
            mock(AIExecutionGateway.class),
            ManifestTestFixtures.objectMapper()
        );

        assertThatThrownBy(() -> factory.bind(
            SpecialistId.of("support-knowledge", "1"),
            WrongQuestion.class,
            Answer.class
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("input")
            .hasMessageContaining("question");
    }

    private TrustedExecutionContext trustedContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                "user-1",
                ExecutionPrincipalType.END_USER
            ),
            new ExecutionSubjectRef("account", "account-1"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "test",
            Set.of("specialist:support-knowledge@1"),
            null,
            Instant.EPOCH
        );
    }

    record Question(String question) {}

    record WrongQuestion(String prompt) {}

    record Answer(String answer) {}
}
