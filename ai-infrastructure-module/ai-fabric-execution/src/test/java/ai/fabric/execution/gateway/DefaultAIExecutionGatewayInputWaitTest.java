package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistManifestMetrics;
import ai.fabric.execution.specialist.manifest.SpecialistResourceMetadata;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.support.TaskExecutorAdapter;

class DefaultAIExecutionGatewayInputWaitTest {

    private static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("billing-advisor", "1");
    private static final SpecialistSchemaId AMOUNT_SCHEMA =
        SpecialistSchemaId.parse("billing-amount-response@1");
    private static final Instant NOW =
        Instant.parse("2026-07-29T10:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void waitsBeforeProviderThenResumesSameInvocationExactlyOnce() {
        Pipeline pipeline = successfulPipeline();
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW)
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");

        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );

        assertThat(waiting.status())
            .isEqualTo(AIExecutionStatus.WAITING_FOR_INPUT);
        assertThat(waiting.output()).isNull();
        assertThat(waiting.needsUserInput().purposeCode())
            .isEqualTo("MISSING_BILLING_AMOUNT");
        assertThat(waiting.needsUserInput().responseContract().schemaId())
            .isEqualTo(AMOUNT_SCHEMA);
        verify(pipeline, never()).execute(any());

        AIExecutionResumeResult<BillingOutput> resumed = gateway.resume(
            resumeRequest(waiting, context, "resume-1", amount(25))
        );

        assertThat(resumed.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        assertThat(resumed.executionResult().invocationId())
            .isEqualTo(waiting.invocationId());
        assertThat(resumed.executionResult().status())
            .isEqualTo(AIExecutionStatus.SUCCEEDED);
        assertThat(resumed.executionResult().output().answer())
            .contains("25");
        verify(pipeline, times(1)).execute(any());
    }

    @Test
    void replaysIdenticalResumeAndRejectsConflictingResponse() {
        Pipeline pipeline = successfulPipeline();
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW)
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );
        AIExecutionResumeRequest first = resumeRequest(
            waiting,
            context,
            "resume-1",
            amount(25)
        );

        AIExecutionResumeResult<BillingOutput> completed =
            gateway.resume(first);
        AIExecutionResumeResult<BillingOutput> replayed =
            gateway.resume(first);
        AIExecutionResumeResult<BillingOutput> conflicting = gateway.resume(
            resumeRequest(waiting, context, "resume-2", amount(40))
        );

        assertThat(completed.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        assertThat(replayed.status())
            .isEqualTo(AIExecutionResumeStatus.REPLAYED);
        assertThat(replayed.executionResult())
            .isSameAs(completed.executionResult());
        assertThat(conflicting.status())
            .isEqualTo(AIExecutionResumeStatus.REJECTED);
        assertThat(conflicting.failure().reason())
            .isEqualTo("INPUT_RESUME_CONFLICT");
        verify(pipeline, times(1)).execute(any());
    }

    @Test
    void allowsCorrectionWithinAttemptLimitAndClosesAfterExhaustion() {
        Pipeline pipeline = successfulPipeline();
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW)
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );

        AIExecutionResumeResult<BillingOutput> invalid = gateway.resume(
            resumeRequest(
                waiting,
                context,
                "invalid-1",
                objectMapper.createObjectNode().put("amount", -1)
            )
        );
        AIExecutionResumeResult<BillingOutput> corrected = gateway.resume(
            resumeRequest(waiting, context, "valid-2", amount(12))
        );

        assertThat(invalid.status())
            .isEqualTo(AIExecutionResumeStatus.REJECTED);
        assertThat(invalid.failure().reason())
            .isEqualTo("INPUT_RESPONSE_INVALID");
        assertThat(invalid.failure().retryable()).isTrue();
        assertThat(corrected.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        verify(pipeline, times(1)).execute(any());

        AIExecutionResult<BillingOutput> secondWait = gateway.execute(
            request(new BillingInput("Assess another refund", null), context)
        );
        gateway.resume(resumeRequest(
            secondWait,
            context,
            "invalid-a",
            objectMapper.createObjectNode().put("amount", 0)
        ));
        AIExecutionResumeResult<BillingOutput> exhausted = gateway.resume(
            resumeRequest(
                secondWait,
                context,
                "invalid-b",
                objectMapper.createObjectNode().put("amount", -2)
            )
        );

        assertThat(exhausted.failure().reason())
            .isEqualTo("INPUT_RESPONSE_ATTEMPTS_EXHAUSTED");
        assertThat(exhausted.failure().retryable()).isFalse();
    }

    @Test
    void concealsWaitFromEveryDifferentAccessBindingAndExpectedSpecialist() {
        Pipeline pipeline = successfulPipeline();
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW)
        );
        TrustedExecutionContext owner = context("service-a", "tenant-1");
        TrustedExecutionContext otherPrincipal =
            context("service-b", "tenant-1");
        TrustedExecutionContext otherTenant =
            context("service-a", "tenant-2");
        TrustedExecutionContext otherSubject = context(
            "service-a",
            "tenant-1",
            "account-99",
            ExecutionSource.APPLICATION,
            "test"
        );
        TrustedExecutionContext otherDeployment = context(
            "service-a",
            "tenant-1",
            "account-42",
            ExecutionSource.APPLICATION,
            "production"
        );
        TrustedExecutionContext otherSource = context(
            "service-a",
            "tenant-1",
            "account-42",
            ExecutionSource.EVENT,
            "test"
        );
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), owner)
        );

        for (TrustedExecutionContext deniedContext : List.of(
            otherPrincipal,
            otherTenant,
            otherSubject,
            otherDeployment,
            otherSource
        )) {
            assertThat(gateway.find(
                waiting.invocationId(),
                deniedContext
            )).isEmpty();
            AIExecutionResumeResult<BillingOutput> denied = gateway.resume(
                resumeRequest(
                    waiting,
                    deniedContext,
                    "wrong-binding-" + deniedContext.correlationId(),
                    amount(25)
                )
            );
            assertThat(denied.status())
                .isEqualTo(AIExecutionResumeStatus.DENIED);
            assertThat(denied.failure().reason())
                .isEqualTo("INPUT_REQUEST_UNAVAILABLE");
        }
        AIExecutionResumeResult<BillingOutput> wrongSpecialist =
            gateway.resume(new AIExecutionResumeRequest(
                SpecialistId.of("other-specialist", "1"),
                waiting.invocationId(),
                waiting.needsUserInput().requestId(),
                amount(25),
                owner,
                "wrong-specialist"
            ));
        assertThat(wrongSpecialist.status())
            .isEqualTo(AIExecutionResumeStatus.DENIED);
        assertThat(wrongSpecialist.failure().reason())
            .isEqualTo("INPUT_REQUEST_UNAVAILABLE");
        assertThat(gateway.resume(
            resumeRequest(waiting, owner, "owner", amount(25))
        ).status()).isEqualTo(AIExecutionResumeStatus.RESUMED);
        verify(pipeline, times(1)).execute(any());
    }

    @Test
    void expiresAndCancelsOnlyThroughTrustedContext() {
        MutableClock clock = new MutableClock(NOW);
        Pipeline pipeline = successfulPipeline();
        DefaultAIExecutionGateway gateway = gateway(pipeline, clock);
        TrustedExecutionContext owner = context("service-a", "tenant-1");
        TrustedExecutionContext other = context("service-b", "tenant-1");
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), owner)
        );

        assertThat(gateway.cancel(waiting.invocationId(), other)).isFalse();
        assertThat(gateway.cancel(waiting.invocationId(), owner)).isTrue();
        assertThat(gateway.resume(
            resumeRequest(waiting, owner, "cancelled", amount(25))
        ).failure().reason()).isEqualTo("INPUT_REQUEST_CANCELLED");

        AIExecutionResult<BillingOutput> expiring = gateway.execute(
            request(new BillingInput("Assess another refund", null), owner)
        );
        clock.advance(Duration.ofMinutes(6));

        AIExecutionResumeResult<BillingOutput> expired = gateway.resume(
            resumeRequest(expiring, owner, "expired", amount(25))
        );

        assertThat(expired.status())
            .isEqualTo(AIExecutionResumeStatus.EXPIRED);
        verify(pipeline, never()).execute(any());
    }

    @Test
    void asyncHandleWaitsThenCompletesAndStateDoesNotSurviveNewGateway() {
        Pipeline pipeline = successfulPipeline();
        MutableClock clock = new MutableClock(NOW);
        DefaultAIExecutionGateway gateway = gateway(pipeline, clock);
        TrustedExecutionContext context = context("service-a", "tenant-1");
        ExecutionHandle handle = gateway.submit(
            request(new BillingInput("Assess this refund", null), context)
        );
        ExecutionSnapshot waiting = gateway.find(
            handle.invocationId(),
            context
        ).orElseThrow();

        assertThat(waiting.handle().status())
            .isEqualTo(ExecutionHandleStatus.WAITING_FOR_INPUT);
        AIExecutionResult<?> waitResult = waiting.result();
        AIExecutionResumeResult<BillingOutput> resumed = gateway.resume(
            resumeRequest(waitResult, context, "async-resume", amount(30))
        );

        assertThat(resumed.executionResult().status())
            .isEqualTo(AIExecutionStatus.SUCCEEDED);
        assertThat(gateway.find(
            handle.invocationId(),
            context
        ).orElseThrow().handle().status())
            .isEqualTo(ExecutionHandleStatus.SUCCEEDED);

        DefaultAIExecutionGateway restarted = gateway(
            successfulPipeline(),
            clock
        );
        assertThat(restarted.find(
            handle.invocationId(),
            context
        )).isEmpty();
        assertThat(restarted.resume(
            resumeRequest(waitResult, context, "restart", amount(30))
        ).status()).isEqualTo(AIExecutionResumeStatus.DENIED);
    }

    @Test
    void enforcesBoundedPendingWaitCapacity() {
        Pipeline pipeline = successfulPipeline();
        AIExecutionProperties.InputWaits waits = waitProperties();
        waits.setMaxPending(1);
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW),
            null,
            new DefaultEffectiveCapabilitiesResolver(),
            waits
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");

        AIExecutionResult<BillingOutput> first = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );
        AIExecutionResult<BillingOutput> second = gateway.execute(
            request(new BillingInput("Assess another refund", null), context)
        );

        assertThat(first.status())
            .isEqualTo(AIExecutionStatus.WAITING_FOR_INPUT);
        assertThat(second.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(second.failure().reason())
            .isEqualTo("INPUT_WAIT_CAPACITY_EXCEEDED");
        verify(pipeline, never()).execute(any());
    }

    @Test
    void retainedReplayStateRemainsBoundedUntilResultTtlExpires() {
        Pipeline pipeline = successfulPipeline();
        AIExecutionProperties.InputWaits waits = waitProperties();
        waits.setMaxPending(1);
        waits.setResultTtl(Duration.ofMinutes(1));
        MutableClock clock = new MutableClock(NOW);
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            clock,
            null,
            new DefaultEffectiveCapabilitiesResolver(),
            waits
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");

        AIExecutionResult<BillingOutput> first = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );
        AIExecutionResumeResult<BillingOutput> completed = gateway.resume(
            resumeRequest(first, context, "complete-first", amount(12))
        );
        AIExecutionResult<BillingOutput> retainedCapacity =
            gateway.execute(
                request(
                    new BillingInput("Assess another refund", null),
                    context
                )
            );

        assertThat(completed.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        assertThat(retainedCapacity.status())
            .isEqualTo(AIExecutionStatus.FAILED);
        assertThat(retainedCapacity.failure().reason())
            .isEqualTo("INPUT_WAIT_CAPACITY_EXCEEDED");

        clock.advance(Duration.ofMinutes(2));

        AIExecutionResult<BillingOutput> afterRetention = gateway.execute(
            request(
                new BillingInput("Assess a third refund", null),
                context
            )
        );

        assertThat(afterRetention.status())
            .isEqualTo(AIExecutionStatus.WAITING_FOR_INPUT);
    }

    @Test
    void failsClosedWhenSpecialistContentChangesDuringWait() {
        Pipeline pipeline = successfulPipeline();
        SpecialistDefinition<BillingInput, BillingOutput> definition =
            definition();
        RegisteredSpecialist initial =
            RegisteredSpecialist.javaDefinition(definition);
        AtomicReference<RegisteredSpecialist> active =
            new AtomicReference<>(initial);
        SpecialistRegistry registry = mock(SpecialistRegistry.class);
        when(registry.find(any(SpecialistId.class))).thenAnswer(invocation ->
            Optional.of(active.get().definition())
        );
        when(registry.findRegistered(any(SpecialistId.class)))
            .thenAnswer(invocation -> Optional.of(active.get()));
        when(registry.require(any(SpecialistId.class))).thenAnswer(invocation ->
            active.get().definition()
        );
        when(registry.requireRegistered(any(SpecialistId.class)))
            .thenAnswer(invocation -> active.get());
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW),
            registry,
            new DefaultEffectiveCapabilitiesResolver(),
            waitProperties()
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );
        active.set(new RegisteredSpecialist(
            definition,
            initial.source(),
            "f".repeat(64),
            initial.sourceDescription(),
            initial.labels()
        ));

        AIExecutionResumeResult<BillingOutput> resumed = gateway.resume(
            resumeRequest(waiting, context, "changed-content", amount(25))
        );

        assertThat(resumed.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        assertThat(resumed.executionResult().status())
            .isEqualTo(AIExecutionStatus.DENIED);
        assertThat(resumed.executionResult().failure().reason())
            .isEqualTo("SPECIALIST_CONTENT_CHANGED");
        verify(pipeline, never()).execute(any());
    }

    @Test
    void failsClosedWhenEffectiveCapabilitiesChangeDuringWait() {
        Pipeline pipeline = successfulPipeline();
        AtomicReference<String> activeProfileHash =
            new AtomicReference<>("profile-a");
        EffectiveCapabilitiesResolver resolver =
            mock(EffectiveCapabilitiesResolver.class);
        when(resolver.resolve(any())).thenAnswer(invocation ->
            effectiveProfile(activeProfileHash.get())
        );
        DefaultAIExecutionGateway gateway = gateway(
            pipeline,
            new MutableClock(NOW),
            null,
            resolver,
            waitProperties()
        );
        TrustedExecutionContext context = context("service-a", "tenant-1");
        AIExecutionResult<BillingOutput> waiting = gateway.execute(
            request(new BillingInput("Assess this refund", null), context)
        );
        activeProfileHash.set("profile-b");

        AIExecutionResumeResult<BillingOutput> resumed = gateway.resume(
            resumeRequest(waiting, context, "changed-profile", amount(25))
        );

        assertThat(resumed.status())
            .isEqualTo(AIExecutionResumeStatus.RESUMED);
        assertThat(resumed.executionResult().status())
            .isEqualTo(AIExecutionStatus.DENIED);
        assertThat(resumed.executionResult().failure().reason())
            .isEqualTo("EFFECTIVE_PROFILE_CHANGED");
        verify(pipeline, never()).execute(any());
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        Clock clock
    ) {
        return gateway(
            pipeline,
            clock,
            null,
            new DefaultEffectiveCapabilitiesResolver(),
            waitProperties()
        );
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        Clock clock,
        SpecialistRegistry suppliedRegistry,
        EffectiveCapabilitiesResolver capabilitiesResolver,
        AIExecutionProperties.InputWaits waits
    ) {
        AIActionRegistry actions = mock(AIActionRegistry.class);
        when(actions.getAllMetadata()).thenReturn(List.of());
        OrchestrationProperties orchestration = orchestrationProperties();
        SpecialistJsonSchemaValidator schemaValidator =
            new SpecialistJsonSchemaValidator();
        SpecialistJsonSchemaRegistry schemas =
            new SpecialistJsonSchemaRegistry(
                List.of(amountSchema()),
                schemaValidator
            );
        SpecialistRegistry registry = suppliedRegistry != null
            ? suppliedRegistry
            : new DefaultSpecialistRegistry(
                List.of(definition()),
                actions,
                orchestration.getModes().keySet()
            );
        return new DefaultAIExecutionGateway(
            registry,
            pipeline,
            new OrchestrationPolicyResolutionStep(orchestration),
            capabilitiesResolver,
            actions,
            new StaticExecutionCapabilityInventory(Set.of(), Set.of()),
            new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(
                new ai.fabric.evidence.AIEvidenceReferenceMapper()
            ),
            mock(SpecialistOutputFinalizer.class),
            null,
            () -> null,
            new TaskExecutorAdapter(Runnable::run),
            clock,
            Duration.ofMinutes(5),
            SpecialistManifestMetrics.noop(),
            schemas,
            schemaValidator,
            new CanonicalJsonSupport(objectMapper),
            waits
        );
    }

    private AIExecutionProperties.InputWaits waitProperties() {
        AIExecutionProperties.InputWaits waits =
            new AIExecutionProperties.InputWaits();
        waits.setEnabled(true);
        waits.setDefaultTtl(Duration.ofMinutes(5));
        waits.setMaxTtl(Duration.ofMinutes(10));
        waits.setMaxAttempts(2);
        waits.setMaxPending(10);
        waits.setMaxRequestsPerInvocation(2);
        waits.setResultTtl(Duration.ofMinutes(5));
        return waits;
    }

    private EffectiveCapabilityProfile effectiveProfile(String profileHash) {
        return new EffectiveCapabilityProfile(
            null,
            "resolver",
            false,
            Set.of(),
            Set.of(),
            Set.of(),
            Set.of(),
            null,
            null,
            profileHash
        );
    }

    private SpecialistDefinition<BillingInput, BillingOutput> definition() {
        SpecialistInputContinuation<BillingInput> continuation =
            new SpecialistInputContinuation<>() {
                @Override
                public String id() {
                    return "billing-amount-input@1";
                }

                @Override
                public Class<BillingInput> inputType() {
                    return BillingInput.class;
                }

                @Override
                public Set<SpecialistSchemaId> responseSchemas() {
                    return Set.of(AMOUNT_SCHEMA);
                }

                @Override
                public Optional<SpecialistInputRequirement> requiredInput(
                    BillingInput input
                ) {
                    return input.amount() == null
                        ? Optional.of(new SpecialistInputRequirement(
                            "MISSING_BILLING_AMOUNT",
                            "What amount should be assessed?",
                            AMOUNT_SCHEMA,
                            Duration.ofMinutes(5),
                            2
                        ))
                        : Optional.empty();
                }

                @Override
                public BillingInput resume(
                    BillingInput originalInput,
                    SpecialistInputRequirement requirement,
                    com.fasterxml.jackson.databind.JsonNode response
                ) {
                    return new BillingInput(
                        originalInput.question(),
                        response.required("amount").decimalValue()
                    );
                }
            };
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Billing advisor",
                "Assesses a billing-resolution path"
            ),
            new SpecialistInstructions(
                "Assess the supplied amount.",
                "Do not infer a missing amount."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                new RequestedCapabilityProfile(
                    false,
                    Set.of(),
                    Set.of(),
                    Set.of(),
                    Set.of()
                ),
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<BillingInput> inputType() {
                    return BillingInput.class;
                }

                @Override
                public void validate(BillingInput input) {
                    if (input.question() == null
                        || input.question().isBlank()) {
                        throw new IllegalArgumentException(
                            "question is required"
                        );
                    }
                    if (input.amount() == null
                        || input.amount().signum() <= 0) {
                        throw new IllegalArgumentException(
                            "positive amount is required"
                        );
                    }
                }

                @Override
                public String renderModelInput(BillingInput input) {
                    return input.question() + " Amount: " + input.amount();
                }

                @Override
                public OrchestrationContext orchestrationContext(
                    BillingInput input
                ) {
                    return OrchestrationContext.builder().build();
                }

                @Override
                public Optional<SpecialistInputContinuation<BillingInput>>
                inputContinuation() {
                    return Optional.of(continuation);
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<BillingOutput> outputType() {
                    return BillingOutput.class;
                }

                @Override
                public BillingOutput project(
                    OrchestrationResult result,
                    List<ai.fabric.evidence.AIEvidenceReference> evidence
                ) {
                    return new BillingOutput(result.getMessage());
                }

                @Override
                public void validate(BillingOutput output) {
                    if (output.answer() == null
                        || output.answer().isBlank()) {
                        throw new IllegalArgumentException(
                            "answer is required"
                        );
                    }
                }
            }
        );
    }

    private Pipeline successfulPipeline() {
        Pipeline pipeline = mock(Pipeline.class);
        when(pipeline.execute(any())).thenAnswer(invocation -> {
            OrchestrationRequest request = invocation.getArgument(0);
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Assessed amount " + request.modelInput())
                .build();
        });
        return pipeline;
    }

    private SpecialistSchemaDefinition amountSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put(
            "$schema",
            "https://json-schema.org/draft/2020-12/schema"
        );
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.putArray("required").add("amount");
        schema.putObject("properties")
            .putObject("amount")
            .put("type", "number")
            .put("exclusiveMinimum", 0)
            .put("maximum", 10_000);
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata(
                AMOUNT_SCHEMA.name(),
                AMOUNT_SCHEMA.version()
            ),
            new SpecialistSchemaSpec(
                SpecialistSchemaDirection.INPUT,
                "2020-12",
                schema
            )
        );
    }

    private OrchestrationProperties orchestrationProperties() {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.setDefaultMode("resolver");
        OrchestrationProperties.ModeOverrides resolver =
            new OrchestrationProperties.ModeOverrides();
        resolver.setActionsEnabled(false);
        resolver.setRetrievalEnabled(false);
        properties.getModes().put("resolver", resolver);
        return properties;
    }

    private AIExecutionRequest<BillingInput> request(
        BillingInput input,
        TrustedExecutionContext context
    ) {
        return AIExecutionRequest.synchronous(
            SPECIALIST_ID,
            input,
            context
        );
    }

    private AIExecutionResumeRequest resumeRequest(
        AIExecutionResult<?> waiting,
        TrustedExecutionContext context,
        String idempotencyKey,
        ObjectNode response
    ) {
        return new AIExecutionResumeRequest(
            waiting.specialistId(),
            waiting.invocationId(),
            waiting.needsUserInput().requestId(),
            response,
            context,
            idempotencyKey
        );
    }

    private ObjectNode amount(int value) {
        return objectMapper.createObjectNode().put("amount", value);
    }

    private TrustedExecutionContext context(
        String principalId,
        String tenantId
    ) {
        return context(
            principalId,
            tenantId,
            "account-42",
            ExecutionSource.APPLICATION,
            "test"
        );
    }

    private TrustedExecutionContext context(
        String principalId,
        String tenantId,
        String subjectId,
        ExecutionSource source,
        String deploymentId
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(
                principalId,
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", subjectId),
            source,
            tenantId,
            deploymentId,
            Set.of("specialist:" + SPECIALIST_ID),
            "correlation-1",
            NOW
        );
    }

    private record BillingInput(
        String question,
        BigDecimal amount
    ) {}

    private record BillingOutput(String answer) {}

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
