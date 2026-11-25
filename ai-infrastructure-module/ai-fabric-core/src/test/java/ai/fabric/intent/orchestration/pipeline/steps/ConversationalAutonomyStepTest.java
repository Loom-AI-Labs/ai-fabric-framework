package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.*;
import ai.fabric.intent.action.*;
import ai.fabric.intent.action.invocation.*;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.workflow.*;
import ai.fabric.llm.structured.*;
import ai.fabric.prompt.*;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

class ConversationalAutonomyStepTest {
    final OrchestrationProperties settings = new OrchestrationProperties();
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AIActionRegistry registry = mock(AIActionRegistry.class);
    final Map<String, AIActionHandler> handlers = new LinkedHashMap<>();
    final AICoreService core = mock(AICoreService.class);
    final ArrayDeque<String> decisions = new ArrayDeque<>();
    final List<AIGenerationRequest> calls = new ArrayList<>();
    final InMemoryPendingActionStore pending = new InMemoryPendingActionStore();
    ConversationalAutonomyStep admission;
    BoundedWorkflowStep workflow;

    @BeforeEach void setup() {
        settings.getAutonomy().setEnabled(true);
        when(registry.getAllMetadata()).thenAnswer(call -> handlers.values().stream().map(AIActionHandler::getActionMetadata).toList());
        when(registry.findMetadata(anyString())).thenAnswer(call -> Optional.ofNullable(handlers.get(call.getArgument(0))).map(AIActionHandler::getActionMetadata));
        when(registry.findHandler(anyString())).thenAnswer(call -> Optional.ofNullable(handlers.get(call.getArgument(0))));
        var prompts = mock(PromptTemplateResolver.class);
        when(prompts.resolve("orchestration/workflow", "system")).thenReturn(new ResolvedPromptTemplate(
            new PromptTemplate(new PromptTemplateKey("orchestration/workflow", "v1", "system"), "Workflow instructions"), List.of("v1")));
        when(core.generateContent(any(), any())).thenAnswer(call -> {
            WorkflowModelCallScope.beforeModelCall(); calls.add(call.getArgument(0));
            return AIGenerationResponse.builder().content(decisions.remove()).build();
        });
        admission = new ConversationalAutonomyStep(settings, registry, mapper);
        workflow = new BoundedWorkflowStep(registry, core, new DefaultStructuredJsonCallExecutor(new StructuredJsonExtractor(), mapper), prompts, new PromptRenderer(), mapper);
        var beans = new StaticListableBeanFactory(); beans.addBean("pending", pending);
        ReflectionTestUtils.setField(workflow, "pendingActions", beans.getBeanProvider(PendingActionStore.class));
    }
    PipelineContext context() {
        var all = handlers.keySet();
        var reads = handlers.entrySet().stream().filter(e -> e.getValue().getActionMetadata().getAccessMode() == ActionAccessMode.READ)
            .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        var writes = new HashSet<>(all); writes.removeAll(reads);
        var profile = new EffectiveCapabilityProfile("customer", "executor", true, Set.of("document"), all,
            reads, writes, OrchestrationPolicy.RagBudgets.defaults(), OrchestrationPolicy.ReadActionResolutionPolicy.defaults(), "profile-hash");
        return PipelineContext.from("Help with the requested business task", OrchestrationContext.builder()
            .conversationId("chat-one").userId("owner").mode("executor").metadata(Map.of("subjectType", "END_USER",
                "subjectId", "owner", "tenantId", "tenant-one", "deploymentId", "brain-one")).build())
            .toBuilder().effectiveCapabilityProfile(profile).build();
    }
    AIActionHandler handler(String name, boolean write) {
        var handler = mock(AIActionHandler.class);
        var metadata = AIActionMetaData.builder().name(name).accessMode(write ? ActionAccessMode.WRITE_ONLY : ActionAccessMode.READ)
            .anonymousAllowed(false).parameterSchemas(Map.of()).requiredParameters(Set.of()).build();
        when(handler.getActionMetadata()).thenReturn(metadata);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.buildPostActionLlmFacts(any(), any())).thenAnswer(call -> {
            ActionResult result = call.getArgument(0); return Optional.of(result.getData() == null ? Map.of() : result.getData().toMap());
        });
        handlers.put(name, handler); return handler;
    }
    void action(String name) { decisions.add("{\"type\":\"ACTION\",\"action\":\"" + name + "\",\"parameters\":{}}"); }
    void complete() { decisions.add("{\"type\":\"COMPLETE\",\"message\":\"The requested result is ready.\"}"); }

    @Test void disabledIsExactNoInferenceParity() {
        settings.getAutonomy().setEnabled(false); var input = context();
        assertThat(admission.process(input)).isSameAs(input); verifyNoInteractions(core);
    }
    @Test void explicitStandardDoesNotAcquireWorkflowOrWriteAuthority() {
        var input = context(); input.getOrchestrationContext().setMode("standard");
        assertThat(admission.process(input)).isSameAs(input); verifyNoInteractions(core);
    }
    @Test void explicitAutonomousCanBeAllowedWithoutChangingTheDefault() {
        settings.getAutonomy().setDefaultEnabled(false); var input = context();
        assertThat(admission.process(input)).isSameAs(input);
        input.getOrchestrationContext().setMode("autonomous");
        assertThat(admission.process(input).getOrchestrationContext().getWorkflowExecution().policy().goalMode()).isTrue();
    }
    @Test void existingPendingConsentStaysInOrdinaryConfirmationLifecycle() {
        var input = context().toBuilder().observedPendingAction(new PendingAction("submit", Map.of(), "Confirm?", Instant.now())).build();
        var bound = admission.process(input);
        assertThat(bound.getOrchestrationContext().getWorkflowExecution()).isNull();
        assertThat(bound.getMetadata()).containsKey("conversationalAutonomy"); verifyNoInteractions(core);
    }
    @Test void existingExplicitHostBindingTakesPrecedence() {
        var input = context();
        var execution = mock(WorkflowExecution.class);
        input.getOrchestrationContext().setWorkflowExecution(execution);
        assertThat(admission.process(input)).isSameAs(input); verifyNoInteractions(core, execution);
    }
    @Test void expiredCallerCannotMakeModelCallsOrInvokeTools() {
        var read = handler("private_read", false); action("private_read");
        var input = context();
        var metadata = new HashMap<String, Object>(input.getOrchestrationContext().getMetadata());
        metadata.put("authExpiresAt", Instant.now().minusSeconds(1).toString());
        input.getOrchestrationContext().setMetadata(metadata);
        var bound = admission.process(input);
        assertThat(bound.getOrchestrationContext().getWorkflowExecution().request().deadline()).isBefore(Instant.now());
        assertThatThrownBy(() -> workflow.process(bound)).isInstanceOf(WorkflowAllowance.Stop.class);
        verifyNoInteractions(core);
        verify(read, never()).executeAction(anyMap(), any());
    }
    @Test void aRevokedTurnCannotContinueAcrossSegments() {
        settings.getAutonomy().setMaxModelCallsPerSegment(1);
        var revoked = new java.util.concurrent.atomic.AtomicBoolean();
        var read = handler("read_business", false);
        when(read.executeAction(anyMap(), any())).thenAnswer(ignored -> {
            revoked.set(true); return ActionResult.builder().success(true).data(ActionPayload.object(Map.of("value", 42))).build();
        });
        var input = context();
        var guard = mock(ai.fabric.intent.orchestration.control.OrchestrationTurnGuard.class);
        doAnswer(ignored -> {
            if (revoked.get()) throw new SecurityException("TURN_REVOKED");
            return null;
        }).when(guard).verifyBeforeOrchestration();
        input.getOrchestrationContext().setOrchestrationTurnGuard(guard);
        action("read_business"); complete();
        var bound = admission.process(input);
        assertThatThrownBy(() -> workflow.process(bound)).isInstanceOf(RuntimeException.class);
        assertThat(calls).hasSize(1); verify(read, times(1)).executeAction(anyMap(), any());
        var execution = bound.getOrchestrationContext().getWorkflowExecution();
        assertThat(execution.checkpoints().find(execution.request().workId()).orElseThrow().steps().getLast().effect())
            .isEqualTo(WorkflowCheckpoint.Effect.EXECUTED);
    }
    @Test void privateCapabilityDenialRetainsTheExistingSignInContract() {
        var read = handler("private_read", false); action("private_read");
        var input = context(); input.getOrchestrationContext().setUserId(null);
        input.getOrchestrationContext().setSessionId("anon-session");
        input.getOrchestrationContext().setMetadata(Map.of("subjectType", "ANONYMOUS_SESSION", "subjectId", "anon-session"));
        var result = workflow.process(admission.process(input)).getIntentResult();
        assertThat(result.getErrorCode()).isEqualTo("AUTHENTICATION_REQUIRED");
        assertThat(mapper.valueToTree(result.getData()).path("authenticationRequirement").path("action").asText()).isEqualTo("private_read");
        verify(read, never()).executeAction(anyMap(), any()); assertThat(calls).hasSize(1);
    }
    @Test void requestLocalJournalContinuesRealSdkSegmentsWithoutUserCorrection() {
        settings.getAutonomy().setMaxModelCallsPerSegment(1);
        var read = handler("read_business", false);
        when(read.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).data(ActionPayload.object(Map.of("value", 42))).build());
        action("read_business"); complete();
        var bound = admission.process(context()); var result = workflow.process(bound).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); assertThat(calls).hasSize(2);
        var saved = bound.getOrchestrationContext().getWorkflowExecution().checkpoints()
            .find(bound.getOrchestrationContext().getWorkflowExecution().request().workId()).orElseThrow();
        assertThat(saved.goal().segmentNumber()).isGreaterThan(1);
        assertThat(saved.usage().modelCalls()).isEqualTo(2);
        verify(read, times(1)).executeAction(anyMap(), any());
    }
    @Test void readNotFoundIsVisibleAndReplannedByModelNotByCode() {
        var lookup = handler("get_item", false); var catalogue = handler("list_items", false);
        when(lookup.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(false).errorCode("UPSTREAM_HTTP_404").build());
        when(catalogue.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).data(ActionPayload.object(Map.of("id", "item-1"))).build());
        action("get_item"); action("list_items"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); assertThat(calls).hasSize(3);
        assertThat(calls.get(1).getPrompt()).contains("UPSTREAM_HTTP_404");
        verify(lookup, times(1)).executeAction(anyMap(), any()); verify(catalogue, times(1)).executeAction(anyMap(), any());
    }
    @Test void authorizationFailureIsNotRecoveredByAnotherTool() {
        var read = handler("private_read", false);
        when(read.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(false).errorCode("UPSTREAM_HTTP_403").build());
        action("private_read"); complete();
        assertThat(workflow.process(admission.process(context())).getIntentResult().isSuccess()).isFalse();
        assertThat(calls).hasSize(1);
    }
    @Test void writeFailureIsNeverReclassifiedAsCorrectableRead() {
        var write = handler("write_business", true);
        when(write.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(false).errorCode("UPSTREAM_HTTP_404").build());
        action("write_business"); complete();
        assertThat(workflow.process(admission.process(context())).getIntentResult().isSuccess()).isFalse();
        verify(write, times(1)).executeAction(anyMap(), any()); assertThat(calls).hasSize(1);
    }
    @Test void successfulWriteWithoutConfirmationRetainsApprovedReceiptAndIsNotReplayed() {
        var write = handler("write_business", true);
        when(write.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).data(ActionPayload.object(Map.of("receipt", "actual-receipt"))).build());
        action("write_business"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue(); assertThat(mapper.valueToTree(result.getData()).path("actions").get(0)
            .path("actionResult").path("data").path("receipt").asText()).isEqualTo("actual-receipt");
        verify(write, times(1)).executeAction(anyMap(), any());
    }
    @Test void successfulToolsRetainTrustedFollowupTargetsOutsidePublicEvidence() {
        var read = handler("read_business", false);
        var target = new ActionTargetRef("actual-item-id", null, "Approved follow-up facts", Map.of("label", "Item"));
        when(read.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true)
            .data(ActionPayload.object(Map.of("id", "actual-item-id"))).pinnedTargets(List.of(target)).build());
        action("read_business"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.getInternalPinnedTargets()).containsExactly(target);
        assertThat(mapper.valueToTree(result.getData()).toString()).doesNotContain("Approved follow-up facts", "pinnedTargets");
    }
    @Test void zeroWritePolicyDoesNotAdvertiseOrInvokeWrites() {
        settings.getAutonomy().setMaxWritesPerSegment(0);
        var write = handler("write_business", true); action("write_business");
        var bound = admission.process(context());
        assertThat(bound.getEffectiveCapabilityProfile().proposableWriteActions()).isEmpty();
        assertThat(bound.getEffectiveCapabilityProfile().visibleActions()).doesNotContain("write_business");
        assertThat(workflow.process(bound).getIntentResult().isSuccess()).isFalse();
        verify(write, never()).executeAction(anyMap(), any()); assertThat(calls).hasSize(1);
    }
    @Test void executedWriteIsNotRetriedWhenOnlyItsObservationProjectionFails() {
        var write = handler("write_business", true);
        when(write.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).build());
        doThrow(new IllegalStateException("projection unavailable")).when(write).buildPostActionLlmFacts(any(), any());
        action("write_business"); complete();
        var bound = admission.process(context()); var result = workflow.process(bound).getIntentResult();
        assertThat(result.isSuccess()).isTrue();
        var execution = bound.getOrchestrationContext().getWorkflowExecution();
        assertThat(execution.checkpoints().find(execution.request().workId()).orElseThrow().steps().getFirst().effect())
            .isEqualTo(WorkflowCheckpoint.Effect.EXECUTED);
        verify(write, times(1)).executeAction(anyMap(), any());
    }
    @Test void confirmationRequiredUsesExistingOwnedPendingActionAndDoesNotWrite() {
        var write = handler("submit_enquiry", true); write.getActionMetadata().setConfirmationRequired(true);
        when(write.getConfirmationMessage(anyMap(), any())).thenReturn("Submit the enquiry?"); action("submit_enquiry");
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("Submit the enquiry?");
        assertThat(pending.peekPendingAction("chat-one", "owner")).isPresent();
        assertThat(pending.peekPendingAction("chat-one", "other-owner")).isEmpty();
        assertThat(result.getActionProposalCandidate()).isNull(); verify(write, never()).executeAction(anyMap(), any());
    }
    @Test void selectedEvidenceNeverReattachesUnapprovedRawActionData() {
        var read = handler("read_business", false);
        when(read.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true)
            .data(ActionPayload.object(Map.of("publicValue", 42, "secretCanary", "DO_NOT_PROJECT"))).build());
        doReturn(Optional.of(Map.of("publicValue", 42))).when(read).buildPostActionLlmFacts(any(), any());
        action("read_business"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(calls.get(1).getPrompt()).doesNotContain("DO_NOT_PROJECT");
        assertThat(mapper.valueToTree(result.getData()).toString()).contains("publicValue").doesNotContain("DO_NOT_PROJECT");
    }
    @Test void approvedKnowledgeUsesExistingProviderAndExactCallerSourceScope() {
        var provider = mock(RAGProvider.class); var beans = new StaticListableBeanFactory(); beans.addBean("rag", provider);
        var knowledge = new AutonomyKnowledgeAction(settings, beans.getBeanProvider(RAGProvider.class)).getHandlers().getFirst();
        handlers.put(AutonomyKnowledgeAction.NAME, knowledge);
        when(provider.performRAGQuery(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of(
            RAGResponse.RAGDocument.builder().id("owned-doc").content("Approved policy").metadata(Map.of("visibility", "PUBLIC_APPROVED")).build())).build());
        decisions.add("{\"type\":\"ACTION\",\"action\":\"ai_search_knowledge\",\"parameters\":{\"query\":\"policy\"}}"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.isSuccess()).isTrue();
        verify(provider).performRAGQuery(argThat(r -> "document".equals(r.getEntityType()) && "tenant-one".equals(r.getAuthContext().getTenantId())));
        assertThat(mapper.valueToTree(result.getData()).path("documents").get(0).path("id").asText()).isEqualTo("owned-doc");
    }
    @Test void blankCustomerCompletionReceivesSdkRepairInsteadOfAnEmptyAnswer() {
        decisions.add("{\"type\":\"COMPLETE\"}"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); assertThat(result.getMessage()).isNotBlank();
        assertThat(calls).hasSize(2); assertThat(calls.getLast().getPrompt()).contains("WORKFLOW_CUSTOMER_ANSWER_REQUIRED");
    }
    @Test void realBusinessClarificationRemainsAModelQuestion() {
        decisions.add("{\"type\":\"NEEDS_INPUT\",\"questions\":[\"What is your preferred contact email?\"]}");
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("What is your preferred contact email?"); assertThat(calls).hasSize(1);
    }
    @Test void explicitSelectionAndTransientFilesReachThePlannerWithoutPersistingFileUrls() {
        var input = context().toBuilder().pinnedTargetsContext("User-selected item: id=item-one, price=499, stock=8").build();
        var file = AIGenerationInputPart.builder().type(AIGenerationInputType.FILE_URL)
            .url("https://approved.example/temporary-file?private-canary=ONE_TURN_ONLY").fileName("guide.pdf").build();
        input.getOrchestrationContext().setTransientInputParts(List.of(file)); complete();
        var result = workflow.process(admission.process(input)).getIntentResult();
        assertThat(result.isSuccess()).isTrue();
        assertThat(calls.getFirst().getPrompt()).contains("item-one", "price=499", "stock=8").doesNotContain("ONE_TURN_ONLY");
        assertThat(calls.getFirst().getInputParts()).containsExactly(file);
        assertThat(calls.getFirst().getTransientInputPolicy().isPersistInputs()).isFalse();
        assertThat(calls.getFirst().getTransientInputPolicy().isLogInputs()).isFalse();
        assertThat(mapper.valueToTree(result.getData()).toString()).doesNotContain("ONE_TURN_ONLY");
    }
    @Test void publishedParallelSearchRequirementIsFedBackBeforeCompletion() {
        var provider = mock(RAGProvider.class); var beans = new StaticListableBeanFactory(); beans.addBean("rag", provider);
        handlers.put(AutonomyKnowledgeAction.NAME, new AutonomyKnowledgeAction(settings, beans.getBeanProvider(RAGProvider.class)).getHandlers().getFirst());
        var read = handler("read_business", false);
        when(read.executeAction(anyMap(), any())).thenReturn(ActionResult.builder().success(true).data(ActionPayload.object(Map.of("value", 42))).build());
        when(provider.performRAGQuery(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of()).build());
        var input = context(); var old = input.getEffectiveCapabilityProfile();
        var cooperation = new OrchestrationPolicy.ReadActionResolutionPolicy(true,
            OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE, List.of(), false, 2, 2, 4, 1, 8000, 2400, 5,
            OrchestrationProperties.ReadActionResolutionRagCooperationMode.PARALLEL_ACTIONS_AND_RAG, false);
        input = input.toBuilder().effectiveCapabilityProfile(new EffectiveCapabilityProfile(old.profile(), old.mode(), true,
            old.effectiveVectorSpaces(), old.visibleActions(), old.executableReadActions(), old.proposableWriteActions(),
            old.ragBudgets(), cooperation, old.profileHash())).build();
        action("read_business"); complete();
        decisions.add("{\"type\":\"ACTION\",\"action\":\"ai_search_knowledge\",\"parameters\":{\"query\":\"policy\"}}"); complete();
        var result = workflow.process(admission.process(input)).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); assertThat(calls).hasSize(4);
        assertThat(calls.get(2).getPrompt()).contains("WORKFLOW_REQUIRED_KNOWLEDGE_SEARCH");
        verify(provider, times(1)).performRAGQuery(any()); verify(read, times(1)).executeAction(anyMap(), any());
    }
    @Test void failedKnowledgeCannotForwardDocumentsOrClaimSuccess() {
        var provider = mock(RAGProvider.class); var beans = new StaticListableBeanFactory(); beans.addBean("rag", provider);
        handlers.put(AutonomyKnowledgeAction.NAME, new AutonomyKnowledgeAction(settings, beans.getBeanProvider(RAGProvider.class)).getHandlers().getFirst());
        when(provider.performRAGQuery(any())).thenReturn(RAGResponse.builder().success(false).documents(List.of(
            RAGResponse.RAGDocument.builder().id("denied").content("PRIVATE_CANARY").build())).build());
        decisions.add("{\"type\":\"ACTION\",\"action\":\"ai_search_knowledge\",\"parameters\":{\"query\":\"policy\"}}"); complete();
        var result = workflow.process(admission.process(context())).getIntentResult();
        assertThat(result.isSuccess()).isFalse(); assertThat(calls).hasSize(1);
        assertThat(mapper.valueToTree(result.getData()).toString()).doesNotContain("PRIVATE_CANARY", "denied");
    }
    @Test void knowledgeReadRetainsConfiguredEvidenceCountAndCharacterLimits() {
        var provider = mock(RAGProvider.class); var beans = new StaticListableBeanFactory(); beans.addBean("rag", provider);
        handlers.put(AutonomyKnowledgeAction.NAME, new AutonomyKnowledgeAction(settings, beans.getBeanProvider(RAGProvider.class)).getHandlers().getFirst());
        when(provider.performRAGQuery(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of(
            RAGResponse.RAGDocument.builder().id("one").content("Approved policy text").build(),
            RAGResponse.RAGDocument.builder().id("two").content("Another result").build())).build());
        var input = context(); var old = input.getEffectiveCapabilityProfile();
        input = input.toBuilder().effectiveCapabilityProfile(new EffectiveCapabilityProfile(old.profile(), old.mode(), true,
            old.effectiveVectorSpaces(), old.visibleActions(), old.executableReadActions(), old.proposableWriteActions(),
            new OrchestrationPolicy.RagBudgets(false, 1, 10, 4, 1, 5, List.of("document"), 0.5),
            old.readActionResolutionPolicy(), old.profileHash())).build();
        decisions.add("{\"type\":\"ACTION\",\"action\":\"ai_search_knowledge\",\"parameters\":{\"query\":\"policy\"}}"); complete();
        var result = workflow.process(admission.process(input)).getIntentResult();
        assertThat(result.isSuccess()).isTrue(); verify(provider).performRAGQuery(argThat(r -> r.getLimit() == 1));
        var documents = mapper.valueToTree(result.getData()).path("documents");
        assertThat(documents).hasSize(1); assertThat(documents.get(0).path("content").asText()).isEqualTo("Appro");
        assertThat(documents.get(0).path("contentTruncated").asBoolean()).isTrue();
    }
    @Test void unapprovedKnowledgeSourceIsDeniedBeforeProviderCall() {
        var provider = mock(RAGProvider.class); var beans = new StaticListableBeanFactory(); beans.addBean("rag", provider);
        var knowledge = new AutonomyKnowledgeAction(settings, beans.getBeanProvider(RAGProvider.class)).getHandlers().getFirst(); handlers.put(AutonomyKnowledgeAction.NAME, knowledge);
        decisions.add("{\"type\":\"ACTION\",\"action\":\"ai_search_knowledge\",\"parameters\":{\"query\":\"policy\",\"vectorSpace\":\"foreign-source\"}}");
        assertThat(workflow.process(admission.process(context())).getIntentResult().isSuccess()).isFalse(); verifyNoInteractions(provider);
    }
}
