package ai.fabric.chat.it.realapi;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.chat.it.ChatSessionIntegrationTestApplication;
import ai.fabric.chat.it.actions.ConfirmableEchoActionHandler;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftContinuation;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.actiondraft.ActionDraftSubmission;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.ActionDraftContinuationAnalyzer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("realapi")
class ActionDraftLifecycleRealApiIntegrationTest {

    @Autowired private RAGOrchestrator orchestrator;
    @Autowired private ActionDraftContinuationAnalyzer analyzer;
    @Autowired private ActionDraftStore drafts;
    @Autowired private ChatSessionService sessions;

    @ParameterizedTest
    @ValueSource(strings = {
        "Cancel this unfinished request. Do not submit anything.",
        "I have changed my mind. Please discard the request we were filling in.",
        "Leave that incomplete request behind; I no longer want to send it."
    })
    void shouldCancelAnIncompleteDraftWithoutExecutingItAndRecordTheTurn(String message) {
        var context = seedDraft();
        ConfirmableEchoActionHandler.resetExecutions();

        var result = orchestrator.orchestrate(message, context);

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsEntry("actionDraftCancelled", true);
        assertThat(drafts.peekDraft(context.getConversationId(), context.getIdentifier())).isEmpty();
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();
        assertThat(sessions.getSession(context.getConversationId(), context.getIdentifier()).getTurns())
            .anySatisfy(turn -> assertThat(turn.getUserQuery()).isEqualTo(message));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "What is the cancellation policy for requests after they are submitted?",
        "Do not cancel the request. What information do you still need?"
    })
    void shouldNotCancelAnInformationQuestionOrNegatedRequest(String message) {
        var context = seedDraft();
        var pipeline = PipelineContext.from(message, context).toBuilder()
            .actionDraftContinuation(new ActionDraftContinuation(
                ConfirmableEchoActionHandler.ACTION_NAME, Map.of(), List.of("message")))
            .build();

        var outcome = analyzer.analyze(pipeline);

        assertThat(outcome.failureType()).isNull();
        assertThat(outcome.cancelled()).isFalse();
        assertThat(drafts.peekDraft(context.getConversationId(), context.getIdentifier())).isPresent();
    }

    @Test
    void shouldAcceptExampleDomainTextButStillWaitForFinalConfirmation() {
        var context = seedDraft();
        ConfirmableEchoActionHandler.resetExecutions();
        String supplied = "demo.acceptance@example.com";
        var submission = context.toBuilder().actionDraftSubmission(new ActionDraftSubmission(
            ConfirmableEchoActionHandler.ACTION_NAME, Map.of("message", supplied))).build();

        var proposal = orchestrator.orchestrate("Use this message: " + supplied, submission);

        assertThat(proposal.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isZero();
        var confirmed = orchestrator.orchestrate("Yes, proceed with that exact message.", context);
        assertThat(confirmed.getType()).as("Confirmed outcome: %s", confirmed)
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(ConfirmableEchoActionHandler.getExecutionCount()).isEqualTo(1);
        assertThat(confirmed.getData().get("actionResult")).isInstanceOfSatisfying(ActionResult.class,
            result -> assertThat(result.getData().toMap()).containsEntry("echo", supplied));
    }

    private OrchestrationContext seedDraft() {
        String owner = "draft-owner-" + UUID.randomUUID();
        String conversation = "draft-chat-" + UUID.randomUUID();
        sessions.recordTurn(conversation, owner, "Prepare a request; I will supply its message later.",
            "What message should the request contain?", Map.of());
        drafts.saveDraft(conversation, owner, new ActionDraft(
            ConfirmableEchoActionHandler.ACTION_NAME, Map.of(), "message", Instant.now(), Instant.now()));
        assertThat(drafts.peekDraft(conversation, owner)).isPresent();
        return OrchestrationContext.builder().userId(owner).conversationId(conversation).build();
    }
}
