package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.RAGResponse;
import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalPersistenceException;
import ai.fabric.execution.action.ActionProposalReceiptStatus;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistConversationBinding;
import ai.fabric.execution.specialist.manifest.SpecialistInteractionCapability;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistManifestMetrics;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.pipeline.DefaultOrchestrationPipeline;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

class DefaultAIExecutionGatewayTest {

    private static final SpecialistId SPECIALIST_ID =
        SpecialistId.of("account-resolver", "1");
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    void executesThroughRealPipelineWithTrustedEnvelopeAndCanonicalEvidence() {
        AtomicReference<PipelineContext> observed = new AtomicReference<>();
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(observed),
            definition(false),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Why is this account blocked?"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.SUCCEEDED);
        assertThat(result.output())
            .isEqualTo(new ResolverOutput("Payment is missing.", 1));
        assertThat(result.evidence()).singleElement().satisfies(reference -> {
            assertThat(reference.evidenceId()).isEqualTo("policy-payment");
            assertThat(reference.vectorSpace()).isEqualTo("account-policy");
            assertThat(reference.safeMetadata())
                .containsEntry("entityId", "PAYMENT_REQUIRED")
                .doesNotContainKey("internalNote");
        });
        assertThat(result.diagnostics())
            .containsEntry("mode", "resolver")
            .containsEntry("strategy", "SINGLE_PASS")
            .containsEntry("evidenceCount", 1);

        PipelineContext pipelineContext = observed.get();
        assertThat(pipelineContext.getOrchestrationRequest()
            .conversationPersistencePolicy())
            .isEqualTo(ConversationPersistencePolicy.NEVER);
        assertThat(pipelineContext.getOrchestrationRequest()
            .trustedExecutionContext().subject().subjectId())
            .isEqualTo("account-42");
        assertThat(pipelineContext.getOriginalQuery())
            .contains("Why is this account blocked?")
            .doesNotContain("Return strict JSON.");
        assertThat(pipelineContext.getOrchestrationRequest().responseInstructions())
            .contains(
                "Objective: Explain account blockers from live profile and policy evidence."
            )
            .contains(
                "Specialist constraints:\nNever infer account state without evidence."
            );
        assertThat(pipelineContext.getOrchestrationContext()
            .getSpecialistInstructions())
            .isEqualTo(
                pipelineContext.getOrchestrationRequest().responseInstructions()
            );
        assertThat(pipelineContext.getOrchestrationContext().getUserId())
            .isEqualTo("account-42");
        assertThat(pipelineContext.getOrchestrationContext().getConversationId()).isNull();
        assertThat(pipelineContext.getEffectiveCapabilityProfile().visibleActions())
            .containsExactly("inspect_account");
        assertThat(pipelineContext.getEffectiveCapabilityProfile().effectiveVectorSpaces())
            .containsExactly("account-policy");
        assertThat(pipelineContext.getOrchestrationPolicy()
            .ragBudgets()
            .retrievalVectorSpacesAllowlist())
            .containsExactly("account-policy");
        assertThat(pipelineContext.getOrchestrationPolicy()
            .ragBudgets()
            .maxSpaces())
            .isEqualTo(1);
    }

    @Test
    void replacesAdapterSuppliedIdentityWithTrustedExecutionSubject() {
        AtomicReference<PipelineContext> observed = new AtomicReference<>();
        SpecialistDefinition<ResolverInput, ResolverOutput> base =
            definition(false);
        SpecialistInputAdapter<ResolverInput> baseInput =
            base.inputAdapter();
        SpecialistDefinition<ResolverInput, ResolverOutput> spoofingDefinition =
            new SpecialistDefinition<>(
                base.identity(),
                base.instructions(),
                base.executionProfile(),
                base.limits(),
                base.delegationPolicy(),
                base.handoffPolicy(),
                new SpecialistInputAdapter<>() {
                    @Override
                    public Class<ResolverInput> inputType() {
                        return baseInput.inputType();
                    }

                    @Override
                    public void validate(ResolverInput input) {
                        baseInput.validate(input);
                    }

                    @Override
                    public String renderModelInput(ResolverInput input) {
                        return baseInput.renderModelInput(input);
                    }

                    @Override
                    public OrchestrationContext orchestrationContext(
                        ResolverInput input
                    ) {
                        return OrchestrationContext.builder()
                            .userId("untrusted-user")
                            .sessionId("untrusted-session")
                            .position("resolver")
                            .metadata(Map.of(
                                OrchestrationContextMetadataKeys.SUBJECT_ID,
                                "untrusted-subject",
                                OrchestrationContextMetadataKeys.SUBJECT_TYPE,
                                "untrusted-subject-type",
                                OrchestrationContextMetadataKeys.AUTH_MODE,
                                "UNTRUSTED",
                                OrchestrationContextMetadataKeys.CALLER_TYPE,
                                "UNTRUSTED",
                                OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
                                "untrusted-deployment",
                                OrchestrationContextMetadataKeys.TENANT_ID,
                                "untrusted-tenant",
                                OrchestrationContextMetadataKeys.GRANTED_SCOPES,
                                List.of("untrusted:scope")
                            ))
                            .build();
                    }
                },
                base.outputAdapter()
            );
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(observed),
            spoofingDefinition,
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Why is this account blocked?"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(observed.get().getOrchestrationContext().getUserId())
            .isEqualTo("account-42");
        assertThat(observed.get().getOrchestrationContext().getSessionId())
            .isNull();
        assertThat(observed.get().getOrchestrationContext().getPosition())
            .isEqualTo("resolver");
        assertThat(observed.get().getOrchestrationContext().getMetadata())
            .containsEntry(
                OrchestrationContextMetadataKeys.SUBJECT_ID,
                "account-42"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.SUBJECT_TYPE,
                "account"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.AUTH_MODE,
                "TRUSTED_APPLICATION"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.CALLER_TYPE,
                "SERVICE"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
                "test"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.TENANT_ID,
                "tenant-1"
            )
            .containsEntry(
                OrchestrationContextMetadataKeys.GRANTED_SCOPES,
                List.copyOf(authorizedScopes())
            );
    }

    @Test
    void deniesAResultContainingEvidenceOutsideTheEffectiveProfile() {
        RAGResponse.RAGDocument unauthorized = RAGResponse.RAGDocument.builder()
            .id("plan-pro")
            .content("Unapproved plan evidence.")
            .score(0.94)
            .metadata(Map.of("vectorSpace", "plans"))
            .build();
        OrchestrationResult orchestrationResult = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Answer influenced by unapproved evidence.")
            .data(Map.of("documents", List.of(unauthorized)))
            .build();
        DefaultAIExecutionGateway gateway = gateway(
            resultPipeline(orchestrationResult, new AtomicReference<>()),
            definition(false),
            Set.of("account-policy", "plans")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(result.output()).isNull();
        assertThat(result.evidence()).isEmpty();
        assertThat(result.failure().reason())
            .isEqualTo("EVIDENCE_VECTOR_SPACE_DENIED");
    }

    @Test
    void persistsConversationOnlyWhenExplicitlyBound() {
        AtomicReference<PipelineContext> observed = new AtomicReference<>();
        AtomicReference<List<Object>> recorded = new AtomicReference<>();
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(observed),
            definition(false),
            Set.of("account-policy"),
            (binding, userInput, assistantOutput, metadata) ->
                recorded.set(List.of(binding, userInput, assistantOutput, metadata))
        );
        TrustedExecutionContext trusted = new TrustedExecutionContext(
            new ExecutionPrincipal("user-42", ExecutionPrincipalType.END_USER),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "test",
            authorizedScopes(),
            "correlation-2",
            NOW
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Why is my account blocked?"),
                trusted,
                new ConversationBinding("user-42", "conversation-7"),
                null,
                null
            )
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(observed.get().getOrchestrationRequest()
            .conversationPersistencePolicy())
            .isEqualTo(ConversationPersistencePolicy.READ_ONLY);
        assertThat(observed.get().getOrchestrationRequest().conversationInput())
            .isEqualTo("Why is my account blocked?");
        assertThat(observed.get().getOriginalQuery())
            .isEqualTo("Why is my account blocked?");
        assertThat(observed.get().getOrchestrationContext().getUserId())
            .isEqualTo("user-42");
        assertThat(observed.get().getOrchestrationContext().getConversationId())
            .isEqualTo("conversation-7");
        assertThat(recorded.get()).satisfies(values -> {
            assertThat(values.get(0))
                .isEqualTo(new ConversationBinding("user-42", "conversation-7"));
            assertThat(values.get(1)).isEqualTo("Why is my account blocked?");
            assertThat(values.get(2)).isEqualTo("Payment is missing.");
            Map<?, ?> metadata = (Map<?, ?>) values.get(3);
            assertThat(metadata.get("_specialist"))
                .isEqualTo("account-resolver@1");
            assertThat(metadata.get("_validated")).isEqualTo(true);
        });
    }

    @Test
    void interactiveReplayKeepsTheOriginalFrozenTurnAndRecordsOnce() {
        AtomicReference<PipelineContext> observed = new AtomicReference<>();
        AtomicInteger pipelineCalls = new AtomicInteger();
        AtomicInteger records = new AtomicInteger();
        PipelineStep step = new PipelineStep() {
            @Override
            public PipelineContext process(PipelineContext context) {
                pipelineCalls.incrementAndGet();
                observed.set(context);
                return context.terminate(
                    OrchestrationResult.builder()
                        .type(OrchestrationResultType.INFORMATION_PROVIDED)
                        .success(true)
                        .message("Payment is missing.")
                        .build()
                );
            }

            @Override
            public String getStepName() {
                return "InteractiveResult";
            }
        };
        EphemeralAIExecutionConversationSnapshotRegistry snapshotRegistry =
            new EphemeralAIExecutionConversationSnapshotRegistry(
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(2)
            );
        DefaultAIExecutionGateway execution = dialogueGateway(
            new DefaultOrchestrationPipeline(List.of(step)),
            snapshotRegistry,
            (binding, userInput, assistantOutput, metadata) ->
                records.incrementAndGet()
        );
        AtomicInteger captures = new AtomicInteger();
        DefaultAIInteractiveExecutionGateway interactive =
            new DefaultAIInteractiveExecutionGateway(
                execution,
                specialistRegistry(dialogueDefinition()),
                (binding, turnId, owner) -> {
                    int capture = captures.getAndIncrement();
                    return new ApprovedConversationSnapshot(
                        turnId,
                        binding.userId(),
                        binding.conversationId(),
                        owner.toString(),
                        (capture == 0 ? "a" : "b").repeat(64),
                        capture,
                        List.of(),
                        NOW
                    );
                },
                snapshotRegistry,
                canonicalJson(),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ZERO,
                Duration.ofMillis(1)
            );
        AIExecutionRequest<ResolverInput> request =
            interactiveRequest(
                new ResolverInput("Why am I blocked?"),
                "browser-request-1"
            );

        AIExecutionResult<ResolverOutput> direct =
            execution.execute(request);
        AIExecutionResult<ResolverOutput> first =
            interactive.execute(request);
        AIExecutionResult<ResolverOutput> replay =
            interactive.execute(request);
        AIExecutionResult<ResolverOutput> conflict =
            interactive.execute(interactiveRequest(
                new ResolverInput("Use another account"),
                "browser-request-1"
            ));

        assertThat(direct.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(direct.failure().reason())
            .isEqualTo("DIALOGUE_OWNER_GATEWAY_REQUIRED");
        assertThat(first.succeeded()).isTrue();
        assertThat(replay.succeeded()).isTrue();
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.diagnostics())
            .containsEntry("interactiveTurn", true)
            .containsEntry("dialogueOwner", true)
            .containsEntry(
                "dialogueOwnerSpecialist",
                "account-resolver@1"
            )
            .containsEntry(
                "conversationSnapshotRevision",
                "a".repeat(64)
            );
        assertThat(replay.diagnostics().keySet())
            .noneMatch(key ->
                key.toLowerCase().contains("token")
                    || key.toLowerCase().contains("history")
            );
        assertThat(conflict.status())
            .isEqualTo(AIExecutionStatus.INVALID);
        assertThat(conflict.failure().reason())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(pipelineCalls).hasValue(1);
        assertThat(records).hasValue(1);
        assertThat(observed.get().getOrchestrationContext()
            .getApprovedConversationSnapshot()
            .revision()).isEqualTo("a".repeat(64));
    }

    @Test
    void doesNotPersistConversationWhenTypedOutputValidationFails() {
        AIExecutionConversationRecorder recorder =
            mock(AIExecutionConversationRecorder.class);
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(true),
            Set.of("account-policy"),
            recorder
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Why is my account blocked?"),
                interactiveContext(),
                new ConversationBinding("user-42", "conversation-7"),
                null,
                null
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason()).isEqualTo("OUTPUT_VALIDATION_FAILED");
        verifyNoInteractions(recorder);
    }

    @Test
    void failsVisiblyWhenConversationPersistenceIsUnavailable() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Why is my account blocked?"),
                interactiveContext(),
                new ConversationBinding("user-42", "conversation-7"),
                null,
                null
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("CONVERSATION_RECORDING_FAILED");
    }

    @Test
    void deniesRequestedActionMissingFromTrustedAuthority() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(Set.of(
                    "specialist:account-resolver@1",
                    "vector:account-policy"
                ))
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_AUTHORITY_INTERSECTION_FAILED");
    }

    @Test
    void deniesVectorSpaceMissingFromDeploymentInventory() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("another-space")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DENIED);
        assertThat(result.failure().reason()).isEqualTo("VECTOR_SPACE_NOT_REGISTERED");
    }

    @Test
    void exposesProviderFailureWithoutFallback() {
        OrchestrationResult providerFailure = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .errorCode("OPENAI_PROVIDER_UNAVAILABLE")
            .message("OpenAI provider is unavailable.")
            .build();
        DefaultAIExecutionGateway gateway = gateway(
            resultPipeline(providerFailure, new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.output()).isNull();
        assertThat(result.failure().reason()).isEqualTo("OPENAI_PROVIDER_UNAVAILABLE");
        assertThat(result.failure().publicMessage()).isEqualTo(
            "OpenAI provider is unavailable."
        );
        assertThat(result.failure().retryable()).isTrue();
    }

    @Test
    void reportsTypedOutputValidationFailure() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(true),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason()).isEqualTo("OUTPUT_VALIDATION_FAILED");
        assertThat(result.failure().publicMessage()).contains("answer is required");
    }

    @Test
    void rejectsFinalOutputThatConflictsWithAuthoritativeSourceFacts() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(
                false,
                SpecialistOutputMode.DIRECT_PROJECTION,
                false,
                true
            ),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.failure().reason()).isEqualTo(
            "OUTPUT_VALIDATION_FAILED"
        );
        assertThat(result.failure().publicMessage()).contains(
            "conflicts with authoritative source facts"
        );
    }

    @Test
    void rejectsIncompleteGroundingBeforeOutputProjection() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false, SpecialistOutputMode.DIRECT_PROJECTION, true),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(result.output()).isNull();
        assertThat(result.failure().reason())
            .isEqualTo("GROUNDING_VALIDATION_FAILED");
        assertThat(result.failure().publicMessage())
            .contains("required policy evidence is missing");
    }

    @Test
    void usesExplicitFinalizerForStructuredGenerationOutput() {
        SpecialistOutputFinalizer finalizer =
            mock(SpecialistOutputFinalizer.class);
        SpecialistDefinition<ResolverInput, ResolverOutput> definition =
            definition(false, SpecialistOutputMode.STRUCTURED_GENERATION);
        when(finalizer.finalizeOutput(
            any(),
            any(),
            any(),
            any(),
            any()
        )).thenReturn(new SpecialistOutputFinalization<>(
            new ResolverOutput("Structured result.", 1),
            Map.of("outputFinalizationAttempts", 1)
        ));
        DefaultAIExecutionGateway gateway = gatewayWithFinalizer(
            successPipeline(new AtomicReference<>()),
            definition,
            Set.of("account-policy"),
            finalizer
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.SUCCEEDED);
        assertThat(result.output().answer()).isEqualTo("Structured result.");
        assertThat(result.diagnostics())
            .containsEntry("outputFinalizationAttempts", 1);
        verify(finalizer).finalizeOutput(
            any(),
            eq("Inspect the account"),
            any(),
            any(),
            any()
        );
    }

    @Test
    void normalizesOnlyAfterRawOutputPassesValidation() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(
                false,
                SpecialistOutputMode.DIRECT_PROJECTION,
                false,
                false,
                true
            ),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.SUCCEEDED);
        assertThat(result.output().answer()).isEqualTo("Canonical result.");

        DefaultAIExecutionGateway invalidGateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(
                true,
                SpecialistOutputMode.DIRECT_PROJECTION,
                false,
                false,
                true
            ),
            Set.of("account-policy")
        );
        AIExecutionResult<ResolverOutput> invalid = invalidGateway.execute(
            AIExecutionRequest.synchronous(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes())
            )
        );

        assertThat(invalid.status()).isEqualTo(AIExecutionStatus.INVALID);
        assertThat(invalid.output()).isNull();
        assertThat(invalid.failure().reason())
            .isEqualTo("OUTPUT_VALIDATION_FAILED");
    }

    @Test
    void replaysIdenticalEphemeralSubmissionByScopedIdempotencyKey() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );
        AIExecutionRequest<ResolverInput> request = new AIExecutionRequest<>(
            SPECIALIST_ID,
            new ResolverInput("Inspect the account"),
            applicationContext(authorizedScopes()),
            null,
            null,
            "request-42"
        );

        ExecutionHandle completed = gateway.submit(request);
        ExecutionHandle duplicate = gateway.submit(request);

        assertThat(completed.durability()).isEqualTo(ExecutionDurability.EPHEMERAL);
        assertThat(completed.status()).isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(completed.deadline())
            .isEqualTo(NOW.plus(SpecialistLimits.defaults().maxDuration()));
        assertThat(gateway.find(
            completed.invocationId(),
            request.trustedExecutionContext()
        ))
            .hasValueSatisfying(snapshot -> {
                assertThat(snapshot.result()).isNotNull();
                assertThat(snapshot.result().succeeded()).isTrue();
            });
        assertThat(duplicate.invocationId())
            .isEqualTo(completed.invocationId());
        assertThat(duplicate.status()).isEqualTo(ExecutionHandleStatus.SUCCEEDED);
        assertThat(duplicate.failureReason()).isNull();
    }

    @Test
    void rejectsChangedSubmissionUnderTheSameScopedIdempotencyKey() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );
        TrustedExecutionContext context =
            applicationContext(authorizedScopes());

        ExecutionHandle original = gateway.submit(new AIExecutionRequest<>(
            SPECIALIST_ID,
            new ResolverInput("Inspect the account"),
            context,
            null,
            null,
            "event-42"
        ));
        ExecutionHandle conflict = gateway.submit(new AIExecutionRequest<>(
            SPECIALIST_ID,
            new ResolverInput("Inspect a different event payload"),
            context,
            null,
            null,
            "event-42"
        ));

        assertThat(original.status()).isEqualTo(
            ExecutionHandleStatus.SUCCEEDED
        );
        assertThat(conflict.invocationId())
            .isNotEqualTo(original.invocationId());
        assertThat(conflict.status()).isEqualTo(
            ExecutionHandleStatus.REJECTED
        );
        assertThat(conflict.failureReason())
            .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void isolatesTheSameIdempotencyKeyAcrossTrustedAccessBindings() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );
        TrustedExecutionContext first =
            applicationContext(authorizedScopes());
        TrustedExecutionContext second = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "account-service",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-84"),
            ExecutionSource.APPLICATION,
            "tenant-2",
            "test",
            authorizedScopes(),
            "correlation-2",
            NOW
        );
        AIExecutionRequest<ResolverInput> firstRequest =
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                first,
                null,
                null,
                "shared-event-id"
            );
        AIExecutionRequest<ResolverInput> secondRequest =
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                second,
                null,
                null,
                "shared-event-id"
            );

        ExecutionHandle firstHandle = gateway.submit(firstRequest);
        ExecutionHandle secondHandle = gateway.submit(secondRequest);

        assertThat(firstHandle.status()).isEqualTo(
            ExecutionHandleStatus.SUCCEEDED
        );
        assertThat(secondHandle.status()).isEqualTo(
            ExecutionHandleStatus.SUCCEEDED
        );
        assertThat(secondHandle.invocationId())
            .isNotEqualTo(firstHandle.invocationId());
        assertThat(gateway.find(
            firstHandle.invocationId(),
            second
        )).isEmpty();
    }

    @Test
    void exposesBoundedQueueRejection() {
        AsyncTaskExecutor rejectingExecutor = mock(AsyncTaskExecutor.class);
        when(rejectingExecutor.submit(any(Runnable.class)))
            .thenThrow(new RejectedExecutionException("queue full"));
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy"),
            rejectingExecutor
        );

        ExecutionHandle handle = gateway.submit(new AIExecutionRequest<>(
            SPECIALIST_ID,
            new ResolverInput("Inspect the account"),
            applicationContext(authorizedScopes()),
            null,
            null,
            "request-queue"
        ));

        assertThat(handle.status()).isEqualTo(ExecutionHandleStatus.REJECTED);
        assertThat(handle.failureReason()).isEqualTo("QUEUE_CAPACITY_EXCEEDED");
    }

    @Test
    void rejectsAnElapsedDeadlineBeforeInvokingThePipeline() {
        DefaultAIExecutionGateway gateway = gateway(
            successPipeline(new AtomicReference<>()),
            definition(false),
            Set.of("account-policy")
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Inspect the account"),
                applicationContext(authorizedScopes()),
                null,
                NOW.minusSeconds(1),
                null
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.DEADLINE_EXCEEDED);
        assertThat(result.failure().reason()).isEqualTo("DEADLINE_EXCEEDED");
    }

    @Test
    void convertsInternalWriteCandidateIntoSafeDurableProposal() {
        ActionProposalCoordinator coordinator =
            mock(ActionProposalCoordinator.class);
        ActionProposalView proposal = new ActionProposalView(
            "action-receipt-1",
            "update_address",
            "Update your billing address?",
            ActionProposalReceiptStatus.PROPOSED,
            NOW,
            NOW.plusSeconds(600)
        );
        when(coordinator.propose(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        )).thenReturn(proposal);
        DefaultAIExecutionGateway gateway = writeGateway(coordinator);

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Update my billing address"),
                applicationContext(Set.of(
                    "specialist:account-resolver@1",
                    "action:update_address"
                )),
                null,
                null,
                "update-address-request-1"
            )
        );

        assertThat(result.status())
            .isEqualTo(AIExecutionStatus.CONFIRMATION_REQUIRED);
        assertThat(result.output()).isNull();
        assertThat(result.actionProposal()).isEqualTo(proposal);
        assertThat(result.failure()).isNull();
        assertThat(result.diagnostics())
            .containsEntry("actionProposal", true)
            .doesNotContainValue("10 Downing Street");
        verify(coordinator).propose(
            eq(result.invocationId()),
            any(),
            any(),
            any(),
            any(),
            eq("update-address-request-1"),
            any()
        );
    }

    @Test
    void doesNotCreateProposalFromErroredCompoundEnvelope() {
        ActionProposalCoordinator coordinator =
            mock(ActionProposalCoordinator.class);
        OrchestrationResult proposal = writeProposalResult();
        OrchestrationResult erroredCompound = OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message("A compound child failed.")
            .errorCode("COMPOUND_CHILD_FAILED")
            .children(List.of(
                proposal,
                OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message("Provider failed.")
                    .errorCode("PROVIDER_FAILED")
                    .build()
            ))
            .build();
        DefaultAIExecutionGateway gateway = writeGateway(
            coordinator,
            erroredCompound
        );

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Update my billing address"),
                applicationContext(Set.of(
                    "specialist:account-resolver@1",
                    "action:update_address"
                )),
                null,
                null,
                "update-address-request-failed-compound"
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.failure().reason())
            .isEqualTo("COMPOUND_CHILD_FAILED");
        assertThat(result.actionProposal()).isNull();
        verifyNoInteractions(coordinator);
    }

    @Test
    void failsVisiblyWhenDurableProposalSupportIsUnavailable() {
        DefaultAIExecutionGateway gateway = writeGateway(null);

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Update my billing address"),
                applicationContext(Set.of(
                    "specialist:account-resolver@1",
                    "action:update_address"
                )),
                null,
                null,
                null
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.output()).isNull();
        assertThat(result.actionProposal()).isNull();
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_PROPOSAL_COORDINATOR_UNAVAILABLE");
    }

    @Test
    void failsVisiblyWhenDurableProposalCannotBePersisted() {
        ActionProposalCoordinator coordinator =
            mock(ActionProposalCoordinator.class);
        when(coordinator.propose(
            any(),
            any(),
            any(),
            any(),
            any(),
            any(),
            any()
        )).thenThrow(new ActionProposalPersistenceException(
            "ACTION_RECEIPT_PERSISTENCE_FAILED",
            "The action proposal could not be persisted. No action was executed.",
            new IllegalStateException("receipt store unavailable")
        ));
        DefaultAIExecutionGateway gateway = writeGateway(coordinator);

        AIExecutionResult<ResolverOutput> result = gateway.execute(
            new AIExecutionRequest<>(
                SPECIALIST_ID,
                new ResolverInput("Update my billing address"),
                applicationContext(Set.of(
                    "specialist:account-resolver@1",
                    "action:update_address"
                )),
                null,
                null,
                "update-address-request-2"
            )
        );

        assertThat(result.status()).isEqualTo(AIExecutionStatus.FAILED);
        assertThat(result.output()).isNull();
        assertThat(result.actionProposal()).isNull();
        assertThat(result.failure().reason())
            .isEqualTo("ACTION_RECEIPT_PERSISTENCE_FAILED");
        assertThat(result.failure().retryable()).isTrue();
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        SpecialistDefinition<ResolverInput, ResolverOutput> definition,
        Set<String> vectorSpaces
    ) {
        return gateway(
            pipeline,
            definition,
            vectorSpaces,
            null,
            new TaskExecutorAdapter(Runnable::run)
        );
    }

    private DefaultAIExecutionGateway dialogueGateway(
        Pipeline pipeline,
        AIExecutionConversationSnapshotRegistry snapshotRegistry,
        AIExecutionConversationRecorder conversationRecorder
    ) {
        AIActionRegistry actionRegistry = actionRegistry();
        OrchestrationProperties properties = orchestrationProperties();
        SpecialistJsonSchemaValidator schemaValidator =
            new SpecialistJsonSchemaValidator();
        return new DefaultAIExecutionGateway(
            specialistRegistry(dialogueDefinition()),
            pipeline,
            new OrchestrationPolicyResolutionStep(properties),
            new DefaultEffectiveCapabilitiesResolver(),
            actionRegistry,
            new StaticExecutionCapabilityInventory(
                Set.of("account-policy"),
                Set.of("inspect_account")
            ),
            new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(
                new AIEvidenceReferenceMapper()
            ),
            mock(SpecialistOutputFinalizer.class),
            conversationRecorder,
            () -> null,
            new TaskExecutorAdapter(Runnable::run),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5),
            SpecialistManifestMetrics.noop(),
            new SpecialistJsonSchemaRegistry(
                List.of(),
                schemaValidator
            ),
            schemaValidator,
            canonicalJson(),
            new AIExecutionProperties.InputWaits(),
            snapshotRegistry
        );
    }

    private DefaultSpecialistRegistry specialistRegistry(
        SpecialistDefinition<ResolverInput, ResolverOutput> definition
    ) {
        return new DefaultSpecialistRegistry(
            List.of(definition),
            actionRegistry(),
            orchestrationProperties().getModes().keySet()
        );
    }

    private CanonicalJsonSupport canonicalJson() {
        return new CanonicalJsonSupport(
            new ObjectMapper().findAndRegisterModules()
        );
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        SpecialistDefinition<ResolverInput, ResolverOutput> definition,
        Set<String> vectorSpaces,
        AIExecutionConversationRecorder conversationRecorder
    ) {
        return gateway(
            pipeline,
            definition,
            vectorSpaces,
            conversationRecorder,
            new TaskExecutorAdapter(Runnable::run)
        );
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        SpecialistDefinition<ResolverInput, ResolverOutput> definition,
        Set<String> vectorSpaces,
        AsyncTaskExecutor taskExecutor
    ) {
        return gateway(
            pipeline,
            definition,
            vectorSpaces,
            null,
            taskExecutor
        );
    }

    private DefaultAIExecutionGateway gateway(
        Pipeline pipeline,
        SpecialistDefinition<ResolverInput, ResolverOutput> definition,
        Set<String> vectorSpaces,
        AIExecutionConversationRecorder conversationRecorder,
        AsyncTaskExecutor taskExecutor
    ) {
        AIActionRegistry actionRegistry = actionRegistry();
        OrchestrationProperties properties = orchestrationProperties();
        return new DefaultAIExecutionGateway(
            new DefaultSpecialistRegistry(
                List.of(definition),
                actionRegistry,
                properties.getModes().keySet()
            ),
            pipeline,
            new OrchestrationPolicyResolutionStep(properties),
            new DefaultEffectiveCapabilitiesResolver(),
            actionRegistry,
            new StaticExecutionCapabilityInventory(
                vectorSpaces,
                Set.of("inspect_account")
            ),
            new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(new AIEvidenceReferenceMapper()),
            mock(SpecialistOutputFinalizer.class),
            conversationRecorder,
            taskExecutor,
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private DefaultAIExecutionGateway gatewayWithFinalizer(
        Pipeline pipeline,
        SpecialistDefinition<ResolverInput, ResolverOutput> definition,
        Set<String> vectorSpaces,
        SpecialistOutputFinalizer finalizer
    ) {
        AIActionRegistry actionRegistry = actionRegistry();
        OrchestrationProperties properties = orchestrationProperties();
        return new DefaultAIExecutionGateway(
            new DefaultSpecialistRegistry(
                List.of(definition),
                actionRegistry,
                properties.getModes().keySet()
            ),
            pipeline,
            new OrchestrationPolicyResolutionStep(properties),
            new DefaultEffectiveCapabilitiesResolver(),
            actionRegistry,
            new StaticExecutionCapabilityInventory(
                vectorSpaces,
                Set.of("inspect_account")
            ),
            new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(new AIEvidenceReferenceMapper()),
            finalizer,
            null,
            new TaskExecutorAdapter(Runnable::run),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private DefaultAIExecutionGateway writeGateway(
        ActionProposalCoordinator coordinator
    ) {
        return writeGateway(coordinator, writeProposalResult());
    }

    private DefaultAIExecutionGateway writeGateway(
        ActionProposalCoordinator coordinator,
        OrchestrationResult orchestrationResult
    ) {
        AIActionMetaData writeMetadata = AIActionMetaData.builder()
            .name("update_address")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .confirmationRequired(true)
            .build();
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        when(actionRegistry.getAllMetadata())
            .thenReturn(List.of(writeMetadata));
        when(actionRegistry.findMetadata("update_address"))
            .thenReturn(Optional.of(writeMetadata));
        OrchestrationProperties properties = orchestrationProperties();
        SpecialistDefinition<ResolverInput, ResolverOutput> definition =
            writeDefinition();
        return new DefaultAIExecutionGateway(
            new DefaultSpecialistRegistry(
                List.of(definition),
                actionRegistry,
                properties.getModes().keySet()
            ),
            resultPipeline(
                orchestrationResult,
                new AtomicReference<>()
            ),
            new OrchestrationPolicyResolutionStep(properties),
            new DefaultEffectiveCapabilitiesResolver(),
            actionRegistry,
            new StaticExecutionCapabilityInventory(
                Set.of(),
                Set.of("update_address")
            ),
            new DefaultSpecialistAuthorityResolver(),
            new OrchestrationEvidenceProjector(
                new AIEvidenceReferenceMapper()
            ),
            mock(SpecialistOutputFinalizer.class),
            null,
            () -> coordinator,
            new TaskExecutorAdapter(Runnable::run),
            Clock.fixed(NOW, ZoneOffset.UTC),
            Duration.ofMinutes(5)
        );
    }

    private OrchestrationResult writeProposalResult() {
        Map<String, Object> parameters = Map.of(
            "addressType",
            "BILLING",
            "streetAddress",
            "10 Downing Street"
        );
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
            .success(false)
            .message("Confirmation required.")
            .actionProposalCandidate(new ActionProposalCandidate(
                "update_address",
                parameters,
                new ActionContext(
                    OrchestrationContext.forUser(
                        "model-supplied-account"
                    ),
                    null,
                    parameters
                )
            ))
            .build();
    }

    private Pipeline successPipeline(AtomicReference<PipelineContext> observed) {
        RAGResponse.RAGDocument document = RAGResponse.RAGDocument.builder()
            .id("policy-payment")
            .content("A verified payment method is required.")
            .score(0.98)
            .source("account-policy-catalog")
            .metadata(Map.of(
                "entityType", "policy",
                "entityId", "PAYMENT_REQUIRED",
                "internalNote", "do not expose"
            ))
            .build();
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Payment is missing.")
            .data(Map.of("documents", List.of(document)))
            .build();
        return resultPipeline(result, observed);
    }

    private Pipeline resultPipeline(
        OrchestrationResult result,
        AtomicReference<PipelineContext> observed
    ) {
        PipelineStep step = new PipelineStep() {
            @Override
            public PipelineContext process(PipelineContext context) {
                observed.set(context);
                return context.terminate(result);
            }

            @Override
            public String getStepName() {
                return "TestResult";
            }
        };
        return new DefaultOrchestrationPipeline(List.of(step));
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition(
        boolean invalidOutput
    ) {
        return definition(
            invalidOutput,
            SpecialistOutputMode.DIRECT_PROJECTION
        );
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput>
    dialogueDefinition() {
        SpecialistDefinition<ResolverInput, ResolverOutput> base =
            definition(false);
        SpecialistInputAdapter<ResolverInput> baseInput =
            base.inputAdapter();
        return new SpecialistDefinition<>(
            base.identity(),
            base.instructions(),
            base.executionProfile(),
            base.limits(),
            base.delegationPolicy(),
            base.handoffPolicy(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<ResolverInput> inputType() {
                    return baseInput.inputType();
                }

                @Override
                public void validate(ResolverInput input) {
                    baseInput.validate(input);
                }

                @Override
                public String renderModelInput(ResolverInput input) {
                    return baseInput.renderModelInput(input);
                }

                @Override
                public String conversationInput(ResolverInput input) {
                    return baseInput.conversationInput(input);
                }

                @Override
                public SpecialistConversationBinding
                    conversationBinding() {
                    return SpecialistConversationBinding.REQUIRED;
                }

                @Override
                public SpecialistInteractionCapability
                    interactionCapability() {
                    return SpecialistInteractionCapability.DIALOGUE_CAPABLE;
                }
            },
            base.outputAdapter()
        );
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> writeDefinition() {
        RequestedCapabilityProfile requested =
            new RequestedCapabilityProfile(
                false,
                Set.of(),
                Set.of("update_address"),
                Set.of(),
                Set.of("update_address")
            );
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Account Resolver",
                "Proposes governed account updates"
            ),
            new SpecialistInstructions(
                "Use only registered current-account actions.",
                "Never bypass confirmation."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                requested,
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<ResolverInput> inputType() {
                    return ResolverInput.class;
                }

                @Override
                public void validate(ResolverInput input) {
                    if (input.question() == null
                        || input.question().isBlank()) {
                        throw new IllegalArgumentException(
                            "question is required"
                        );
                    }
                }

                @Override
                public String renderModelInput(ResolverInput input) {
                    return input.question();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<ResolverOutput> outputType() {
                    return ResolverOutput.class;
                }

                @Override
                public ResolverOutput project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    return new ResolverOutput(
                        result.getMessage(),
                        evidence.size()
                    );
                }

                @Override
                public void validate(ResolverOutput output) {}
            }
        );
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition(
        boolean invalidOutput,
        SpecialistOutputMode outputMode
    ) {
        return definition(invalidOutput, outputMode, false);
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition(
        boolean invalidOutput,
        SpecialistOutputMode outputMode,
        boolean rejectGrounding
    ) {
        return definition(
            invalidOutput,
            outputMode,
            rejectGrounding,
            false
        );
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition(
        boolean invalidOutput,
        SpecialistOutputMode outputMode,
        boolean rejectGrounding,
        boolean rejectFinalOutput
    ) {
        return definition(
            invalidOutput,
            outputMode,
            rejectGrounding,
            rejectFinalOutput,
            false
        );
    }

    private SpecialistDefinition<ResolverInput, ResolverOutput> definition(
        boolean invalidOutput,
        SpecialistOutputMode outputMode,
        boolean rejectGrounding,
        boolean rejectFinalOutput,
        boolean normalizeOutput
    ) {
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            true,
            Set.of("account-policy"),
            Set.of("inspect_account"),
            Set.of("inspect_account"),
            Set.of()
        );
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SPECIALIST_ID,
                "Account Resolver",
                "Explains current-account blockers"
            ),
            new SpecialistInstructions(
                "Explain account blockers from live profile and policy evidence.",
                "Never infer account state without evidence."
            ),
            new SpecialistExecutionProfile(
                "resolver",
                requested,
                ExecutionStrategy.SINGLE_PASS,
                SpecialistWritePolicy.DISABLED
            ),
            SpecialistLimits.defaults(),
            new SpecialistInputAdapter<>() {
                @Override
                public Class<ResolverInput> inputType() {
                    return ResolverInput.class;
                }

                @Override
                public void validate(ResolverInput input) {
                    if (input.question() == null || input.question().isBlank()) {
                        throw new IllegalArgumentException("question is required");
                    }
                }

                @Override
                public String renderModelInput(ResolverInput input) {
                    return input.question();
                }

                @Override
                public String conversationInput(ResolverInput input) {
                    return input.question();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<ResolverOutput> outputType() {
                    return ResolverOutput.class;
                }

                @Override
                public SpecialistOutputMode outputMode() {
                    return outputMode;
                }

                @Override
                public String outputContractInstructions() {
                    return "Return strict JSON.";
                }

                @Override
                public ResolverOutput project(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    return new ResolverOutput(
                        invalidOutput ? "" : result.getMessage(),
                        evidence.size()
                    );
                }

                @Override
                public void validateGrounding(
                    OrchestrationResult result,
                    List<AIEvidenceReference> evidence
                ) {
                    if (rejectGrounding) {
                        throw new IllegalArgumentException(
                            "required policy evidence is missing"
                        );
                    }
                }

                @Override
                public void validate(ResolverOutput output) {
                    if (output.answer() == null || output.answer().isBlank()) {
                        throw new IllegalArgumentException("answer is required");
                    }
                }

                @Override
                public void validateFinalOutput(
                    ResolverOutput output,
                    OrchestrationResult sourceResult,
                    List<AIEvidenceReference> evidence
                ) {
                    validate(output);
                    if (rejectFinalOutput) {
                        throw new IllegalArgumentException(
                            "output conflicts with authoritative source facts"
                        );
                    }
                }

                @Override
                public ResolverOutput normalizeFinalOutput(
                    ResolverOutput output,
                    OrchestrationResult sourceResult,
                    List<AIEvidenceReference> evidence
                ) {
                    return normalizeOutput
                        ? new ResolverOutput(
                            "Canonical result.",
                            output.evidenceCount()
                        )
                        : output;
                }
            }
        );
    }

    private AIActionRegistry actionRegistry() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder()
                .name("inspect_account")
                .accessMode(ActionAccessMode.READ)
                .groundingEligible(true)
                .readActionResolutionEligible(true)
                .build()
        ));
        return registry;
    }

    private OrchestrationProperties orchestrationProperties() {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.setDefaultMode("resolver");
        OrchestrationProperties.ModeOverrides mode =
            new OrchestrationProperties.ModeOverrides();
        mode.setActionsEnabled(true);
        mode.setRetrievalEnabled(true);
        OrchestrationProperties.RagModeOverrides rag =
            new OrchestrationProperties.RagModeOverrides();
        rag.setFanoutEnabled(true);
        rag.setMaxSpaces(2);
        rag.setRetrievalVectorSpacesAllowlist(
            List.of("account-policy", "plans")
        );
        mode.setRag(rag);
        properties.getModes().put("resolver", mode);
        return properties;
    }

    private TrustedExecutionContext applicationContext(Set<String> scopes) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal("account-service", ExecutionPrincipalType.SERVICE),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "test",
            scopes,
            "correlation-1",
            NOW
        );
    }

    private TrustedExecutionContext interactiveContext() {
        return new TrustedExecutionContext(
            new ExecutionPrincipal("user-42", ExecutionPrincipalType.END_USER),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.INTERACTIVE,
            "tenant-1",
            "test",
            authorizedScopes(),
            "correlation-chat",
            NOW
        );
    }

    private AIExecutionRequest<ResolverInput> interactiveRequest(
        ResolverInput input,
        String idempotencyKey
    ) {
        return new AIExecutionRequest<>(
            SPECIALIST_ID,
            input,
            interactiveContext(),
            new ConversationBinding(
                "user-42",
                "conversation-7"
            ),
            null,
            idempotencyKey
        );
    }

    private Set<String> authorizedScopes() {
        return Set.of(
            "specialist:account-resolver@1",
            "action:inspect_account",
            "vector:account-policy"
        );
    }

    private record ResolverInput(String question) {}

    private record ResolverOutput(String answer, int evidenceCount) {}
}
