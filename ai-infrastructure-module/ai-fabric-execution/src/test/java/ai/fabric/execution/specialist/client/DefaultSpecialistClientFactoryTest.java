package ai.fabric.execution.specialist.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.gateway.ExecutionSnapshot;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.DefaultSpecialistManifestCompiler;
import ai.fabric.execution.specialist.manifest.ManifestTestFixtures;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
    void convertsTypedDtosAcrossTheInteractiveGateway() {
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
        AIInteractiveExecutionGateway interactiveGateway =
            mock(AIInteractiveExecutionGateway.class);
        ObjectNode rawOutput = ManifestTestFixtures.objectMapper()
            .createObjectNode()
            .put("answer", "Use the approved recovery process.");
        when(interactiveGateway.execute(any())).thenReturn(
            new AIExecutionResult<>(
                "exec-interactive-1",
                SpecialistId.of("support-knowledge", "1"),
                AIExecutionStatus.SUCCEEDED,
                rawOutput,
                List.of(),
                Map.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH
            )
        );
        SpecialistClientFactory factory = new DefaultSpecialistClientFactory(
            registry,
            mock(AIExecutionGateway.class),
            ManifestTestFixtures.objectMapper()
        );
        SpecialistClient<Question, Answer> client = factory.bind(
            SpecialistId.of("support-knowledge", "1"),
            Question.class,
            Answer.class
        );
        SpecialistInvocation<Question> invocation =
            new SpecialistInvocation<>(
                new Question("How do I reset MFA?"),
                trustedContext(),
                new ConversationBinding("user-1", "conversation-1"),
                null,
                "interactive-1"
            );

        AIExecutionResult<Answer> result = client.executeInteractive(
            invocation,
            interactiveGateway
        );

        assertThat(result.output().answer())
            .isEqualTo("Use the approved recovery process.");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<AIExecutionRequest> request =
            ArgumentCaptor.forClass(AIExecutionRequest.class);
        verify(interactiveGateway).execute(request.capture());
        assertThat(request.getValue().input())
            .isInstanceOf(ObjectNode.class);
        assertThat(
            ((ObjectNode) request.getValue().input()).path("question")
                .textValue()
        ).isEqualTo("How do I reset MFA?");
        assertThat(request.getValue().conversationBinding())
            .isEqualTo(invocation.conversationBinding());
        assertThat(request.getValue().idempotencyKey())
            .isEqualTo("interactive-1");
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

    @Test
    @SuppressWarnings("unchecked")
    void bindsAndCachesNativeJavaSpecialistsWithoutJsonConversion() {
        SpecialistId id = SpecialistId.of("native-support", "1");
        SpecialistInputAdapter<Question> inputAdapter =
            mock(SpecialistInputAdapter.class);
        SpecialistOutputAdapter<Answer> outputAdapter =
            mock(SpecialistOutputAdapter.class);
        SpecialistDefinition<Question, Answer> definition =
            mock(SpecialistDefinition.class);
        when(inputAdapter.inputType()).thenReturn(Question.class);
        when(outputAdapter.outputType()).thenReturn(Answer.class);
        when(definition.inputAdapter()).thenReturn(inputAdapter);
        when(definition.outputAdapter()).thenReturn(outputAdapter);
        var registered = new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.JAVA,
            CanonicalJsonSupport.sha256("native-support"),
            "test:native-support",
            Map.of()
        );
        var registry = mock(
            ai.fabric.execution.specialist.SpecialistRegistry.class
        );
        when(registry.requireRegistered(id)).thenReturn(registered);
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        Answer answer = new Answer("Use the native specialist.");
        when(gateway.execute(any())).thenReturn(new AIExecutionResult<>(
            "exec-native",
            id,
            AIExecutionStatus.SUCCEEDED,
            answer,
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

        SpecialistClient<Question, Answer> first = factory.bind(
            id,
            Question.class,
            Answer.class
        );
        SpecialistClient<Question, Answer> second = factory.bind(
            id,
            Question.class,
            Answer.class
        );
        AIExecutionResult<Answer> result = first.execute(
            new Question("What is the native path?"),
            trustedContext()
        );

        assertThat(second).isSameAs(first);
        assertThat(result.output()).isSameAs(answer);
    }

    @Test
    void submitsFindsAndCancelsSchemaBoundExecutionWithTypedOutput() {
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
        ExecutionHandle handle = new ExecutionHandle(
            "exec-async",
            ExecutionDurability.EPHEMERAL,
            ExecutionHandleStatus.SUCCEEDED,
            null,
            Instant.parse("2026-07-29T11:00:00Z"),
            null
        );
        ObjectNode rawOutput = ManifestTestFixtures.objectMapper()
            .createObjectNode()
            .put("answer", "Use the approved recovery process.");
        AIExecutionResult<ObjectNode> rawResult =
            new AIExecutionResult<>(
                "exec-async",
                SpecialistId.of("support-knowledge", "1"),
                AIExecutionStatus.SUCCEEDED,
                rawOutput,
                List.of(),
                Map.of(),
                null,
                Instant.EPOCH,
                Instant.EPOCH
            );
        when(gateway.submit(any())).thenReturn(handle);
        when(gateway.find(eq("exec-async"), any()))
            .thenReturn(Optional.of(
                new ExecutionSnapshot(handle, rawResult)
            ));
        when(gateway.cancel(eq("exec-async"), any())).thenReturn(true);
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
        TrustedExecutionContext context = trustedContext();
        SpecialistInvocation<Question> invocation =
            new SpecialistInvocation<>(
                new Question("How do I reset MFA?"),
                context,
                null,
                null,
                "event-1"
            );

        ExecutionHandle submitted = client.submit(invocation);
        Optional<SpecialistExecutionSnapshot<Answer>> snapshot =
            client.find("exec-async", context);
        boolean cancelled = client.cancel("exec-async", context);

        assertThat(submitted).isSameAs(handle);
        assertThat(snapshot).hasValueSatisfying(found -> {
            assertThat(found.handle()).isSameAs(handle);
            assertThat(found.result().output().answer())
                .isEqualTo("Use the approved recovery process.");
        });
        assertThat(cancelled).isTrue();
        verify(gateway).submit(any());
        verify(gateway).find("exec-async", context);
        verify(gateway).cancel("exec-async", context);
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
