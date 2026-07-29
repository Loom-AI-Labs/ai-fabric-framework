package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ActionDraftPromptAugmentationStepTest {

    @Test
    void shouldExposeOnlyActionAndPublicFieldNamesToIntentPrompt() {
        InMemoryActionDraftStore store = new InMemoryActionDraftStore();
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionMetaData metadata = actionMetadata();
        when(registry.findMetadata("update_address"))
            .thenReturn(Optional.of(metadata));
        store.saveDraft(
            "conversation-1",
            "user-1",
            new ActionDraft(
                "update_address",
                Map.of(
                    "street", "16 Dairy Drive",
                    "subscriptionId", "subscription-secret"
                ),
                "city, postalCode",
                Instant.now(),
                Instant.now()
            )
        );
        PipelineContext context = PipelineContext.from(
            "London, SW1A 1AA",
            OrchestrationContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .build()
        );

        PipelineContext updated =
            new ActionDraftPromptAugmentationStep(store, registry)
                .process(context);

        assertThat(updated.getIntentExtractionSystemInstructions())
            .contains("INCOMPLETE ACTION DRAFT CONTEXT")
            .contains("action=update_address")
            .contains("street")
            .contains("city")
            .contains("postalCode")
            .contains("first decide whether the actual current user message")
            .contains("broader domain meaning")
            .contains("A new question")
            .doesNotContain("16 Dairy Drive")
            .doesNotContain("subscriptionId")
            .doesNotContain("subscription-secret");
        assertThat(updated.getPinnedTargetsContext()).isNull();
        assertThat(updated.getActionDraftContinuation().collectedParams())
            .containsEntry("street", "16 Dairy Drive")
            .doesNotContainKey("subscriptionId");
        assertThat(updated.toString())
            .doesNotContain("16 Dairy Drive")
            .doesNotContain("subscription-secret");
    }

    @Test
    void shouldSkipDraftWhenConversationPersistenceIsDisabled() {
        InMemoryActionDraftStore store = new InMemoryActionDraftStore();
        AIActionRegistry registry = mock(AIActionRegistry.class);
        OrchestrationContext orchestrationContext =
            OrchestrationContext.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "London",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.NEVER
        );

        PipelineContext updated =
            new ActionDraftPromptAugmentationStep(store, registry)
                .process(PipelineContext.from(request));

        assertThat(updated.getActionDraftContinuation()).isNull();
        assertThat(updated.getPinnedTargetsContext()).isNull();
        assertThat(updated.getIntentExtractionSystemInstructions()).isNull();
    }

    private AIActionMetaData actionMetadata() {
        AIActionParamSchema publicString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(true)
            .build();
        AIActionParamSchema hiddenString = AIActionParamSchema.builder()
            .type(AIActionParamType.STRING)
            .askUser(false)
            .visibility("INTERNAL")
            .build();
        return AIActionMetaData.builder()
            .name("update_address")
            .parameterSchemas(Map.of(
                "street", publicString,
                "city", publicString,
                "postalCode", publicString,
                "subscriptionId", hiddenString
            ))
            .requiredParameters(Set.of(
                "street",
                "city",
                "postalCode",
                "subscriptionId"
            ))
            .build();
    }
}
