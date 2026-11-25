package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.control.DefaultConversationControlGateway;
import ai.fabric.chat.control.JdbcConversationControlStore;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.conversation.ConversationStateBinding;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkingSetTargetSeedingStepTest {

    @Test
    void shouldNotSeedWhenNoIntentRequiresTargetResolution() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("info")
            .requiresTargetResolution(false)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("compare", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        verify(service, never()).getSession(anyString(), anyString());
    }

    @Test
    void shouldActivatePersistedActionTargetsAfterTargetDependentIntentExtraction() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSession session = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(List.of(
                ChatTurn.builder().build(),
                ChatTurn.builder().build(),
                ChatTurn.builder().build()
            ))
            .sessionMetadata(Map.of(
                "lastResolvedTargetsTurnIndex", 2,
                "lastResolvedTargets", List.of(
                    Map.of(
                        "id", "stock-42",
                        "vectorSpace", "product",
                        "contentText", "make: Example model: One",
                        "contentTextTruncated", false,
                        "metadata", Map.of("stockId", "stock-42"),
                        "originSource", "ACTION_RESULT_ITEMS"
                    )
                )
            ))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(session);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(3);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare_previous_results")
            .requiresTargetResolution(true)
            .build();
        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();
        PipelineContext context = PipelineContext.from("Of those, which is best?", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = new WorkingSetTargetSeedingStep(service, properties).process(context);

        assertThat(updated.getResolvedTargets()).hasSize(1);
        assertThat(updated.getResolvedTargets().getFirst().getId()).isEqualTo("stock-42");
        assertThat(updated.getResolvedTargets().getFirst().getContentText()).contains("Example model");
        assertThat(updated.getResolvedTargets().getFirst().getSource())
            .isEqualTo(ResolvedTargetSource.ACTION_RESULT_ITEMS);
        assertThat(updated.getPinnedTargetsContext())
            .startsWith("PINNED TARGETS (previously pinned; selected for this target-dependent turn):");
        assertThat(updated.getMetadata().get("workingSetTargetSeeding"))
            .isEqualTo(Map.of(
                "seeded", true,
                "count", 1,
                "source", "PINNED_TARGETS",
                "activation", "MODEL_REQUESTED_TARGET_RESOLUTION"
            ));
    }

    @Test
    void shouldNotInferTargetRequirementFromReferentialWordingWhenModelMissesFlag() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSession session = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(List.of(ChatTurn.builder().build(), ChatTurn.builder().build()))
            .sessionMetadata(Map.of(
                "lastResolvedTargetsTurnIndex", 1,
                "lastResolvedTargets", List.of(Map.of(
                    "id", "record-42",
                    "vectorSpace", "catalog",
                    "originSource", "ACTION_RESULT_ITEMS"
                ))
            ))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(session);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        Intent referenced = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Of those, which option has the lowest operating cost?")
            .optimizedQuery("which of those options has the lowest operating cost")
            .requiresTargetResolution(false)
            .build();
        Intent independent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Explain the delivery policy")
            .optimizedQuery("delivery policy")
            .requiresTargetResolution(false)
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();
        PipelineContext context = PipelineContext.from(
                "Of those, which option has the lowest operating cost, and what is the delivery policy?",
                orchestrationContext
            )
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("Show me the available options")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(referenced, independent)).build())
            .build();

        PipelineContext updated = new WorkingSetTargetSeedingStep(service, properties).process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        assertThat(referenced.getRequiresTargetResolution()).isFalse();
        assertThat(independent.getRequiresTargetResolution()).isFalse();
        assertThat(updated.getMetadata()).doesNotContainKey("workingSetTargetSeeding");
        verify(service, never()).getSession(anyString(), anyString());
    }

    @Test
    void shouldNotActivatePersistedTargetsForBroadDiscoveryQuery() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("Show all available options")
            .requiresTargetResolution(false)
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();
        PipelineContext context = PipelineContext.from("Show all available options", orchestrationContext)
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("Earlier request")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = new WorkingSetTargetSeedingStep(service, properties).process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        assertThat(intent.getRequiresTargetResolution()).isFalse();
        verify(service, never()).getSession(anyString(), anyString());
    }

    @Test
    void shouldNotActivateExpiredOrFutureDatedPersistedTargets() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(2);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare_previous_results")
            .requiresTargetResolution(true)
            .build();
        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();
        PipelineContext context = PipelineContext.from("Compare those", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        List<Map<String, Object>> storedTargets = List.of(Map.of(
            "id", "stock-42",
            "vectorSpace", "product",
            "originSource", "ACTION_RESULT_ITEMS"
        ));
        ChatSession expired = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(List.of(
                ChatTurn.builder().build(),
                ChatTurn.builder().build(),
                ChatTurn.builder().build(),
                ChatTurn.builder().build()
            ))
            .sessionMetadata(Map.of(
                "lastResolvedTargetsTurnIndex", 1,
                "lastResolvedTargets", storedTargets
            ))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(expired);

        PipelineContext expiredResult = new WorkingSetTargetSeedingStep(service, properties).process(context);

        assertThat(expiredResult.getResolvedTargets()).isEmpty();
        assertThat(expiredResult.getMetadata().get("workingSetTargetSeeding"))
            .isEqualTo(Map.of("seeded", false));

        ChatSession futureDated = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(expired.getTurns())
            .sessionMetadata(Map.of(
                "lastResolvedTargetsTurnIndex", 5,
                "lastResolvedTargets", storedTargets
            ))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(futureDated);

        PipelineContext futureResult = new WorkingSetTargetSeedingStep(service, properties).process(context);

        assertThat(futureResult.getResolvedTargets()).isEmpty();
        assertThat(futureResult.getMetadata().get("workingSetTargetSeeding"))
            .isEqualTo(Map.of("seeded", false));
    }

    @Test
    void shouldSeedFromLatestWorkingSetWhenIntentRequiresTargetResolution() {
        ChatSessionService service = mock(ChatSessionService.class);

        ChatTurn turn = ChatTurn.builder()
            .userQuery("q")
            .aiResponse("a")
            .timestamp(LocalDateTime.now())
            .turnMetadata(Map.of(
                "_workingSet", Map.of(
                    "topDocumentRefs", List.of(
                        Map.of("id", "2", "vectorSpace", "product", "metadata", Map.of("sku", "ELEC-PHONE-002"))
                    )
                )
            ))
            .build();

        ChatSession session = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(List.of(turn))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(session);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare")
            .requiresTargetResolution(true)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("compare both", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isNotEmpty();
        assertThat(updated.getResolvedTargets().getFirst().getSource()).isEqualTo(ResolvedTargetSource.WORKING_SET);
        assertThat(updated.getMetadata()).containsKey("workingSetTargetSeeding");
        verify(service).getSession("conv-1", "user-1");
    }

    @Test
    void shouldNotSeedWhenRequestAttachmentsExist() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("add_to_cart")
            .requiresTargetResolution(true)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("att-1")
                .vectorSpace("product")
                .build()))
            .build();

        PipelineContext context = PipelineContext.from("add it", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        verify(service, never()).getSession(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldSeedCanonicalPinnedTargetsWithoutReadingLegacySession(boolean workflow) {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(3);
        DefaultConversationControlGateway gateway = gateway();
        ConversationScope scope = new ConversationScope(
            "tenant-1", "deployment-1", "conv-1", "user-1"
        );
        gateway.status(scope);
        gateway.updateOrchestrationState(scope, 0L, metadata -> {
            metadata.put("lastResolvedTargetsTurnIndex", 1L);
            metadata.put("lastResolvedTargets", List.of(Map.of(
                "id", "stock-42",
                "vectorSpace", "dealer-vehicle",
                "metadata", Map.of("stockId", "stock-42"),
                "originSource", "ACTION_RESULT_ITEMS"
            )));
            return metadata;
        });
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare_selected_vehicle")
            .requiresTargetResolution(true)
            .build();
        OrchestrationContext orchestration = canonicalContext(1L);
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Compare the selected vehicle",
            orchestration,
            trustedContext(),
            ConversationPersistencePolicy.READ_ONLY,
            null,
            null,
            null,
            ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose.GENERAL,
            ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy.MODEL_DIRECTED,
            new ConversationStateBinding(
                "tenant-1", "deployment-1", "conv-1", "user-1", 0L
            )
        )).toBuilder()
            .intentResponse(workflow ? null : MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        var seed = new WorkingSetTargetSeedingStep(service, properties, gateway);
        if (workflow) bindWorkflow(context);
        PipelineContext updated = workflow ? new ConversationalAutonomyTargetsStep(seed).process(context) : seed.process(context);

        assertThat(updated.getResolvedTargets()).singleElement().satisfies(target -> {
            assertThat(target.getId()).isEqualTo("stock-42");
            assertThat(target.getVectorSpace()).isEqualTo("dealer-vehicle");
            assertThat(target.getSource()).isEqualTo(ResolvedTargetSource.ACTION_RESULT_ITEMS);
        });
        verify(service, never()).getSession(anyString(), anyString());
    }

    @Test void autonomousReferencesCannotReadAnotherOwnersCanonicalState() {
        var service = mock(ChatSessionService.class);
        var properties = new ChatSessionProperties(); properties.setEnabled(true);
        var gateway = gateway(); var owned = new ConversationScope("tenant-1", "deployment-1", "conv-1", "user-1");
        gateway.status(owned);
        gateway.updateOrchestrationState(owned, 0L, metadata -> {
            metadata.put("lastWorkingSet", List.of(Map.of("id", "PRIVATE_REFERENCE_CANARY", "contentText", "Private content")));
            return metadata;
        });
        var other = OrchestrationContext.builder().userId("user-2").conversationId("conv-1")
            .approvedConversationSnapshot(new ApprovedConversationSnapshot(
                "turn-2", "user-2", "conv-1", "specialist-1", "a".repeat(64), 1L,
                List.of(), Instant.parse("2026-10-16T01:00:00Z")))
            .build();
        var context = PipelineContext.from(new OrchestrationRequest("Show the previous item", other, trustedContext(),
            ConversationPersistencePolicy.READ_ONLY, null, null, null,
            ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose.GENERAL,
            ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy.MODEL_DIRECTED,
            new ConversationStateBinding("tenant-1", "deployment-1", "conv-1", "user-2", 0L)));
        bindWorkflow(context);
        var result = new ConversationalAutonomyTargetsStep(new WorkingSetTargetSeedingStep(service, properties, gateway)).process(context);
        assertThat(result.isShouldTerminate()).isTrue();
        assertThat(result.getIntentResult().getErrorCode()).isEqualTo("CONVERSATION_STATE_UNAVAILABLE");
        assertThat(result.getResolvedTargets()).isEmpty();
        assertThat(result.getPinnedTargetsContext()).isNullOrEmpty();
        verify(service, never()).getSession(anyString(), anyString());
    }

    private void bindWorkflow(PipelineContext context) {
        var workflow = mock(ai.fabric.intent.orchestration.workflow.WorkflowExecution.class);
        var authority = mock(ai.fabric.intent.orchestration.workflow.WorkflowExecution.Authority.class);
        when(workflow.authority()).thenReturn(authority); when(authority.continueWithinRequest()).thenReturn(true);
        context.getOrchestrationContext().setWorkflowExecution(workflow);
    }

    private DefaultConversationControlGateway gateway() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:target-seeding-" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return new DefaultConversationControlGateway(
            new JdbcConversationControlStore(dataSource, new ObjectMapper(), true),
            Clock.fixed(Instant.parse("2026-10-16T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    private OrchestrationContext canonicalContext(long sourceTurnCount) {
        return OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .approvedConversationSnapshot(new ApprovedConversationSnapshot(
                "turn-1",
                "user-1",
                "conv-1",
                "specialist-1",
                "a".repeat(64),
                sourceTurnCount,
                List.of(),
                Instant.parse("2026-10-16T01:00:00Z")
            ))
            .build();
    }

    private TrustedExecutionContext trustedContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal("service-1", ExecutionPrincipalType.SERVICE),
            null,
            ExecutionSource.APPLICATION,
            "tenant-1",
            "deployment-1",
            java.util.Set.of(),
            "correlation-1",
            Instant.parse("2026-10-16T01:00:00Z")
        );
    }
}
