package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.execution.context.*;
import ai.fabric.execution.specialist.*;
import ai.fabric.execution.specialist.manifest.*;
import ai.fabric.execution.state.*;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.pipeline.DefaultOrchestrationPipeline;
import ai.fabric.intent.orchestration.pipeline.steps.BoundedWorkflowStep;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.orchestration.workflow.*;
import ai.fabric.llm.structured.*;
import ai.fabric.prompt.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.task.support.TaskExecutorAdapter;

/** Real manifest/schema, native gateway, pipeline runner, and JDBC scheduler; only the model transport is scripted. */
class CompiledGoalExecutionIntegrationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true}) @SuppressWarnings("unchecked")
    void compiledGoalCrossesSegmentsThenResumesVerifiedDirectiveWithoutChangingNativeInput(boolean structuredOutput) {
        var mapper = ManifestTestFixtures.objectMapper();
        ObjectNode resource = mapper.valueToTree(ManifestTestFixtures.manifest());
        ObjectNode spec = (ObjectNode) resource.path("spec");
        ((ObjectNode) spec.path("execution")).put("strategy", "BOUNDED_ITERATIVE").set("workflow", mapper.valueToTree(
            new WorkflowPolicy(true, 4, 0, structuredOutput ? 1 : 2, 1, Duration.ofSeconds(120),
                new WorkflowGoalPolicy(true, Duration.ofDays(1), Duration.ZERO, 8, 4, List.of()))));
        ((ObjectNode) spec.path("limits")).put("maxDuration", "PT120S");
        ((ObjectNode) spec.path("grounding")).put("requirement", "NONE").put("requireEvidenceCitations", false).putArray("sources");
        ((ObjectNode) spec.path("capabilities").path("retrieval")).put("enabled", false).putArray("vectorSpaces");
        ((ObjectNode) spec.path("conversation")).put("binding", "DISABLED").put("recordValidatedTurns", false);
        ((ObjectNode) spec.path("input")).put("primaryTextPointer", "/query").put("conversationTextPointer", "/query");
        ((ObjectNode) spec.path("output")).put("mode", "DIRECT_PROJECTION").put("directProjectorRef", "goal-result@1");
        if (structuredOutput) {
            ((ObjectNode) spec.path("output")).put("mode", "STRUCTURED_GENERATION").remove("directProjectorRef");
        }
        var originalSchema = ManifestTestFixtures.inputSchema();
        ObjectNode schema = originalSchema.spec().schema().deepCopy();
        var properties = (ObjectNode) schema.path("properties"); properties.set("query", properties.remove("question"));
        schema.putArray("required").add("query");
        var inputSchema = new SpecialistSchemaDefinition(originalSchema.apiVersion(), originalSchema.kind(), originalSchema.metadata(),
            new SpecialistSchemaSpec(SpecialistSchemaDirection.INPUT, "2020-12", schema));
        var base = ManifestTestFixtures.compilationContext(ManifestTestFixtures.promptProfile(), inputSchema, ManifestTestFixtures.outputSchema());
        var compilation = new SpecialistCompilationContext(base.schemaRegistry(), base.promptProfileRegistry(), base.groundingValidatorRegistry(),
            base.finalOutputValidatorRegistry(), new SpecialistDirectOutputProjectorRegistry(List.of(SpecialistDirectOutputProjector.named("goal-result@1",
                (result, evidence) -> mapper.createObjectNode().put("answer", result.getMessage())))), base.outputNormalizerRegistry(),
            base.inputContinuationRegistry(), base.schemaValidator(), base.definitionValidator(), base.canonicalJson(), mapper,
            Set.of(), "goal.json", ManifestTestFixtures.HASH);
        var compiled = new DefaultSpecialistManifestCompiler().compile(mapper.convertValue(resource, SpecialistManifest.class), compilation).specialist();
        var registry = new DefaultSpecialistRegistry(List.of(compiled), compilation.definitionValidator());
        var actions = mock(AIActionRegistry.class); when(actions.getAllMetadata()).thenReturn(List.of());
        var model = mock(AICoreService.class); var calls = new AtomicInteger(); var revision = new AtomicInteger(1);
        when(model.generateContent(any(), any())).thenAnswer(invocation -> {
            WorkflowModelCallScope.beforeModelCall();
            AIGenerationRequest request = invocation.getArgument(0);
            assertThat(request.getPrompt()).contains("directiveContext", "requestRevision");
            int call = calls.incrementAndGet();
            String reply = call <= 4 ? "{\"type\":\"INVALID\"}" : revision.get() == 1
                ? "{\"type\":\"NEEDS_INPUT\",\"questions\":[\"Which connection?\"]}"
                : "{\"type\":\"COMPLETE\",\"message\":\"Prepared with the supplied connection.\"}";
            return AIGenerationResponse.builder().content(reply).build();
        });
        var prompts = mock(PromptTemplateResolver.class);
        when(prompts.resolve("orchestration/workflow", "system")).thenReturn(new ResolvedPromptTemplate(
            new PromptTemplate(new PromptTemplateKey("orchestration/workflow", "v1", "system"), "Use configured goal tools and current directives."), List.of("v1")));
        var step = new BoundedWorkflowStep(actions, model, new DefaultStructuredJsonCallExecutor(new StructuredJsonExtractor(), mapper),
            prompts, new PromptRenderer(), mapper);
        var configuration = new OrchestrationProperties(); configuration.setDefaultMode("deep");
        var mode = new OrchestrationProperties.ModeOverrides(); mode.setRetrievalEnabled(true);
        configuration.getModes().put("deep", mode);
        var clock = Clock.systemUTC(); var executor = new TaskExecutorAdapter(Runnable::run);
        var finalizer = mock(SpecialistOutputFinalizer.class);
        doAnswer(invocation -> {
            WorkflowModelCallScope.beforeModelCall();
            return new SpecialistOutputFinalization<>(mapper.createObjectNode().put("answer", "Prepared with the supplied connection."), Map.of());
        }).when(finalizer).finalizeOutput(any(), anyString(), any(), any(), anyList());
        var nativeGateway = new DefaultAIExecutionGateway(registry, new DefaultOrchestrationPipeline(List.of(step)),
            new OrchestrationPolicyResolutionStep(configuration), new DefaultEffectiveCapabilitiesResolver(), actions,
            new StaticExecutionCapabilityInventory(Set.of("support-policy"), Set.of()), new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(new AIEvidenceReferenceMapper()), finalizer, null, executor, clock, Duration.ofHours(1));
        var dataSource = new JdbcDataSource(); dataSource.setURL("jdbc:h2:mem:compiled-goal-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var repository = new JdbcDurableExecutionRepository(dataSource, true);
        var security = new DurableExecutionSecurity(mapper, "compiled-goal-encryption-secret-0001", "compiled-goal-fingerprint-secret-0002");
        var codec = new DurableExecutionPayloadCodec(mapper, registry, security);
        var claimed = new ThreadLocal<GoalExecutionClaim>();
        nativeGateway.setWorkflowExecutionBindingProvider((id, definition, request, context) -> {
            var record = repository.findById(id).orElseThrow();
            return new WorkflowExecution(new BoundedWorkflowRunner.Request(id, record.requestFingerprint(), record.accessFingerprint(),
                record.specialistContentHash(), record.deadline(), false), definition.executionProfile().workflowPolicy(),
                new DurableWorkflowCheckpointStore(repository, security, mapper, clock, record), new WorkflowExecution.Authority() {
                    public boolean resumeGoal() { return claimed.get().resumeRequested(); }
                    public Map<String, Object> directiveContext() { return Map.of("requestRevision", revision.get(), "query", "Use the owned connection reference"); }
                    public void verify(BoundedWorkflowRunner.Request request) { assertThat(claimed.get().authorityReference()).isEqualTo("work-authority"); }
                    public String fingerprint(Map<String, Object> values) { return security.canonicalHash(values); }
                    public BoundedWorkflowRunner.Outcome reconcile(WorkflowCheckpoint.Step step) { throw new AssertionError(); }
                    public Map<String, Object> observation(ai.fabric.intent.action.AIActionMetaData metadata, ai.fabric.intent.action.ActionContext context,
                        ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome outcome) { return Map.of(); }
                });
        });
        var gateway = new DurableAIExecutionGateway(nativeGateway, nativeGateway, registry, repository, codec, security,
            new DurableExecutionSubmissionPolicy(), executor, clock, Duration.ofSeconds(30), Duration.ofDays(1), 20, 3, false);
        var hostClaims = new AtomicInteger();
        gateway.setDurableGoalExecutionHost(new DurableGoalExecutionHost() {
            public AIExecutionRequest<?> prepareSubmission(GoalExecutionRequest request) { return request.request(); }
            public AIExecutionResult<?> executeSegment(GoalExecutionClaim claim, AIExecutionRequest<?> persisted,
                java.util.function.Function<AIExecutionRequest<?>, AIExecutionResult<?>> execute) {
                hostClaims.incrementAndGet(); claimed.set(claim);
                try { return execute.apply(persisted); } finally { claimed.remove(); }
            }
        });
        var now = Instant.now();
        var trusted = new TrustedExecutionContext(new ExecutionPrincipal("owner", ExecutionPrincipalType.END_USER),
            new ExecutionSubjectRef("resource", "owned-resource"), ExecutionSource.INTERACTIVE, "tenant", "deployment",
            Set.of("specialist:support-knowledge@1", "vector:support-policy"), "correlation", now);
        JsonNode nativeInput = mapper.createObjectNode().put("query", "Prepare the selected resource");
        var request = new AIExecutionRequest<>(compiled.id(), nativeInput, trusted, null, now.plusSeconds(86400), "goal");
        var accepted = gateway.submitGoal(new GoalExecutionRequest(request, "work-authority"));
        for (int i = 0; i < 6 && gateway.find(accepted.invocationId(), trusted).orElseThrow().handle().status() == ExecutionHandleStatus.YIELDED; i++) gateway.recover();
        var waiting = gateway.find(accepted.invocationId(), trusted).orElseThrow();
        assertThat(waiting.handle().status()).as("%s", waiting.result()).isEqualTo(ExecutionHandleStatus.PAUSED);
        assertThat(waiting.handle().failureReason()).isEqualTo("NEEDS_INPUT");
        assertThat(waiting.handle().deadline()).isEqualTo(request.deadline().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
        var before = gateway.findWorkflowCheckpoint(accepted.invocationId(), trusted).orElseThrow();
        assertThat(before.state()).isEqualTo(WorkflowCheckpoint.State.NEEDS_INPUT);
        revision.set(2);
        assertThat(gateway.controlGoal(accepted.invocationId(), GoalExecutionControl.RESUME, trusted)).isTrue();
        for (int i = 0; i < 3 && gateway.find(accepted.invocationId(), trusted).orElseThrow().handle().status() == ExecutionHandleStatus.YIELDED; i++) gateway.recover();
        var completed = gateway.find(accepted.invocationId(), trusted).orElseThrow();
        assertThat(completed.handle().status()).as("%s", completed.result()).isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(((JsonNode) completed.result().output()).path("answer").asText()).contains("supplied connection");
        var after = gateway.findWorkflowCheckpoint(accepted.invocationId(), trusted).orElseThrow();
        assertThat(after.usage().modelCalls()).isGreaterThan(before.usage().modelCalls());
        assertThat(after.requestFingerprint()).isEqualTo(before.requestFingerprint());
        assertThat(codec.unprotectRequest(repository.findById(accepted.invocationId()).orElseThrow()).input()).isEqualTo(nativeInput);
        assertThat(hostClaims.get()).isGreaterThanOrEqualTo(4);
        var rejectedEnvelope = mapper.createObjectNode().put("query", "Prepare").put("_loomaiGoal", "not-a-schema-field");
        assertThatThrownBy(() -> ((SpecialistInputAdapter<JsonNode>) compiled.definition().inputAdapter()).validate(rejectedEnvelope))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
