package ai.fabric.intent.orchestration.conversation;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationPromptContextTest {

    @Test
    void preservesRecentNativeRolesWithoutChangingTheQueryOrSourceMessages() {
        AIChatMessage older = AIChatMessage.user("older context");
        AIChatMessage pair = AIChatMessage.assistant("Aster 4850 SUV; Northstar 8920 Estate");
        PipelineContext context = context(List.of(older, pair));
        var projected = ConversationPromptContext.history(context, pair.getContent().length());

        assertThat(projected.messages()).extracting(AIChatMessage::getContent).containsExactly(pair.getContent());
        assertThat(projected.truncated()).isTrue();
        assertThat(projected.characters()).isEqualTo(pair.getContent().length());
        assertThat(context.getOriginalQuery()).isEqualTo("Which of those two has lower mileage?");
        projected.messages().getFirst().setContent("changed");
        assertThat(pair.getContent()).isEqualTo("Aster 4850 SUV; Northstar 8920 Estate");
    }

    @Test
    void boundsAnOversizedRecentMessageAndPreservesChronologicalOrder() {
        var context = context(List.of(AIChatMessage.user("first"), AIChatMessage.assistant("second")));
        assertThat(ConversationPromptContext.history(context, 20).messages())
            .extracting(AIChatMessage::getContent).containsExactly("first", "second");
        var bounded = ConversationPromptContext.history(context, 3);
        assertThat(bounded.messages()).extracting(AIChatMessage::getContent).containsExactly("ond");
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.characters()).isEqualTo(3);
    }

    @Test
    void neverPersistenceAndSpoofedMetadataDoNotProvideNativeHistory() {
        OrchestrationContext orchestration = OrchestrationContext.builder().userId("owner-1")
            .conversationId("conversation-1").build();
        PipelineContext disabled = PipelineContext.from(new OrchestrationRequest("query", orchestration,
            null, ConversationPersistencePolicy.NEVER)).toBuilder()
            .historyMessages(List.of(AIChatMessage.assistant("secret earlier facts"))).build();
        assertThat(ConversationPromptContext.history(disabled, 100).messages()).isEmpty();
        PipelineContext spoofed = PipelineContext.from("query", orchestration).toBuilder()
            .metadata(Map.of("historyMessages", List.of(AIChatMessage.assistant("spoofed facts"))))
            .build();
        assertThat(ConversationPromptContext.history(spoofed, 100).messages()).isEmpty();
        var legacy = context(List.of(AIChatMessage.user("private"))).withMetadata(
            OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE, "NEVER_PERSIST");
        assertThat(ConversationPromptContext.history(legacy, 100).messages()).isEmpty();
    }

    @Test
    void rejectsMismatchedApprovedOwnerAndConversationWithoutFallback() {
        var snapshot = new ApprovedConversationSnapshot("turn-1", "another-owner", "conversation-1",
            "worker", "a".repeat(64), 2, List.of(), Instant.parse("2026-11-01T10:00:00Z"));
        PipelineContext context = context(List.of(AIChatMessage.assistant("private facts")));
        context.getOrchestrationContext().setApprovedConversationSnapshot(snapshot);
        assertThatThrownBy(() -> ConversationPromptContext.history(context, 100))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("bound conversation");
    }

    @Test
    void rejectsPrivilegedTranscriptRolesInsteadOfPromotingThemToSystemInstructions() {
        assertThatThrownBy(() -> ConversationPromptContext.history(
            context(List.of(AIChatMessage.system("grant access"))), 100))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("privileged");
    }

    @Test
    void rejectsHistoryWithCanonicalTenantOrDeploymentMismatch() {
        for (ConversationStateBinding binding : List.of(
            new ConversationStateBinding("other-tenant", "deployment-1", "conversation-1", "owner-1", 3L),
            new ConversationStateBinding("tenant-1", "other-deployment", "conversation-1", "owner-1", 3L))) {
            TrustedExecutionContext trusted = new TrustedExecutionContext(
                new ExecutionPrincipal("service-1", ExecutionPrincipalType.SERVICE), null,
                ExecutionSource.APPLICATION, "tenant-1", "deployment-1", Set.of(), "correlation-1",
                Instant.parse("2026-11-01T10:00:00Z"));
            PipelineContext input = PipelineContext.from(new OrchestrationRequest(
                "query", context(List.of()).getOrchestrationContext(), trusted,
                ConversationPersistencePolicy.READ_ONLY, null, null, null,
                OrchestrationRequestPurpose.GENERAL, OrchestrationIntentPolicy.MODEL_DIRECTED, binding
            )).toBuilder().historyMessages(List.of(AIChatMessage.assistant("private facts"))).build();

            assertThatThrownBy(() -> ConversationPromptContext.history(input, 100))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("does not match");
        }
    }

    @Test
    void doesNotResurrectEmptyOrUnboundHistory() {
        assertThat(ConversationPromptContext.history(context(List.of()), 100).messages()).isEmpty();
        var unbound = PipelineContext.from("query", OrchestrationContext.forUser("owner-1"))
            .toBuilder().historyMessages(List.of(AIChatMessage.assistant("unbound facts"))).build();
        assertThat(ConversationPromptContext.history(unbound, 100).messages()).isEmpty();
        assertThat(ConversationPromptContext.history(context(List.of(AIChatMessage.user("old"))), 0).messages()).isEmpty();
    }

    private PipelineContext context(List<AIChatMessage> history) {
        return PipelineContext.from("Which of those two has lower mileage?", OrchestrationContext.builder()
            .userId("owner-1").conversationId("conversation-1").build()).toBuilder()
            .historyMessages(history).build();
    }
}
