package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.ActionProposalCandidate;
import ai.fabric.intent.action.invocation.DefaultGovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationStatus;
import ai.fabric.intent.action.invocation.GovernedActionInvocationSupport;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.action.policy.ActionPostPolicyEngine;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecision;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorParamSupport;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorRule;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.intent.orchestration.pipeline.steps.ActionEvidenceSupport.EvidenceBundle;
import ai.fabric.intent.orchestration.pipeline.steps.ActionExecutableValidationSupport.ActionExecutableValidation;
import ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.ActionParamValidation;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationDecisionSupport.ConfirmationResolutionDecision;
import ai.fabric.intent.orchestration.pipeline.steps.ConfirmationResolutionSupport.ConfirmationResolutionOutcome;
import ai.fabric.intent.orchestration.pipeline.steps.PostActionGenerationSupport.PostActionGenerationOutcome;
import ai.fabric.intent.orchestration.pipeline.steps.PostActionGenerationSupport.ResolvedPostActionGeneration;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.rag.EmbeddingQueryComposer;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;

import static ai.fabric.intent.orchestration.pipeline.steps.ActionEvidenceSupport.buildEvidenceBundle;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionExecutableValidationSupport.validateExecutableActionParams;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionMetadataVisibilitySupport.hasActionParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionMetadataVisibilitySupport.publicActionMetadata;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionMetadataVisibilitySupport.publicProvidedParameters;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.actionExecutableValidationMessage;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.mergeExecutableValidation;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.publicActionParamValidationMetadata;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.publicMissingRequiredParameters;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.suppressConfirmationGateParameter;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParamValidationSupport.validateRequiredActionParams;
import static ai.fabric.intent.orchestration.pipeline.steps.ActionParameterSupport.CONFIRMATION_ACCEPTED_PARAMETER;

/**
 * Pipeline step that handles the extracted intents (ACTION, INFORMATION, etc.).
 * 
 * <p>This step routes intents to appropriate handlers based on intent type:</p>
 * <ul>
 *   <li>{@code ACTION} - Executes via registered action handlers</li>
 *   <li>{@code INFORMATION} - Performs RAG search/generation</li>
 *   <li>{@code OUT_OF_SCOPE} - Returns guidance message</li>
 * </ul>
 * 
 * <p><strong>Order:</strong> 60 (after intent extraction)</p>
 * 
 * <p><strong>Security:</strong> Actions are denied for anonymous users by default.
 * Anonymous execution must be explicitly enabled in the action contract, and action handlers may
 * declare {@code @ActionAllowed} for additional access control.</p>
 * 
 * @see ai.fabric.intent.action.AIActionRegistry
 * @see RAGProvider
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
public class IntentHandlingStep implements PipelineStep {
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "IntentHandling";
    private static final int STEP_ORDER = 60;
    
    // RAG defaults
    // Data keys
    private static final String DATA_KEY_ACTION = "action";
    private static final String DATA_KEY_METADATA = "metadata";
    private static final String DATA_KEY_ACTION_RESULT = "actionResult";
    private static final String DATA_KEY_CONFIRMATION_MESSAGE = "confirmationMessage";
    private static final String DATA_KEY_ANSWER = "answer";
    private static final String DATA_KEY_DOCUMENTS = "documents";
    private static final String DATA_KEY_RAG_RESPONSE = "ragResponse";
    private static final String DATA_KEY_REQUIRES_GENERATION = "requiresGeneration";
    private static final String DATA_KEY_DETAILS = "details";
    private static final String DATA_KEY_RESULTS = "results";
    private static final String DATA_KEY_CONFIDENCE_SCORE = "confidenceScore";
    
    // Metadata values
    
    // Error/Message constants
    private static final String ERROR_MSG_UNKNOWN_INTENT = "Unknown intent type: ";
    private static final String ERROR_MSG_MISSING_ACTION_NAME = "Intent is missing an action name.";
    private static final String ERROR_MSG_NO_HANDLER = "No action handler registered for action '";
    private static final String ERROR_MSG_ACTION_NOT_PERMITTED_ANON = "Action not permitted for anonymous users.";
    private static final String ERROR_MSG_ACTION_NOT_PERMITTED_USER = "Action not permitted for this user.";
    private static final String MSG_OUT_OF_SCOPE =
        "I can help with this store's approved product, policy, comparison, cart, and order questions.";
    private static final String MSG_ALL_PROCESSED = "All intents processed successfully.";
    private static final String MSG_SOME_FAILED = "Some intents failed. See results for details.";
    
    // Provider-agnostic error codes (for deterministic client handling)
    private static final String ERROR_CODE_ACTION_NOT_FOUND = "ACTION_NOT_FOUND";

    private static final String RAG_NO_CONTEXT_MESSAGE = "No relevant context found.";
    private static final String DATA_KEY_CONFIRMATION_REQUIRED = "confirmationRequired";
    private static final String DATA_KEY_MISSING_REQUIRED_PARAMETERS = "missingRequiredParameters";
    private static final String DATA_KEY_PROVIDED_PARAMETERS = "providedParameters";
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final AIActionRegistry actionHandlerRegistry;
    private final ObjectProvider<RAGProvider> ragProvider;
    private final AIServiceConfig aiServiceConfig;
    private final OrchestrationProperties orchestrationProperties;
    private final AIEntityConfigurationLoader entityConfigurationLoader;
    private final PendingActionStore pendingActionStore;
    private final ActionDraftStore actionDraftStore;
    private final ActionBatchSupport actionBatchSupport;
    private final PostActionGenerationSupport postActionGenerationSupport;
    private final RagResponseGenerationSupport ragResponseGenerationSupport;
    private final AdvancedRagSupport advancedRagSupport;
    private final VectorSpaceSelectionSupport vectorSpaceSelectionSupport;
    private final InformationRagExecutionSupport informationRagExecutionSupport;
    private final InformationAdvancedRagExecutionSupport informationAdvancedRagExecutionSupport;
    private final ConfirmationResolutionSupport confirmationResolutionSupport;
    private final ActionContextParamResolutionSupport actionContextParamResolutionSupport;
    @Autowired(required = false)
    private ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationInterceptorCatalogProvider;
    @Autowired(required = false)
    private ObjectProvider<ActionPostPolicyEngine> actionPostPolicyEngineProvider;
    @Autowired(required = false)
    private ObjectProvider<ReadActionResolutionService> readActionResolutionServiceProvider;

    public IntentHandlingStep(AIActionRegistry actionHandlerRegistry,
                              ObjectProvider<RAGProvider> ragProvider,
                              AICoreService aiCoreService,
                              AIServiceConfig aiServiceConfig,
                              ObjectProvider<AdvancedRAGProvider> advancedRagProvider,
                              VectorSpaceRoutingProperties vectorSpaceRoutingProperties,
                              RankBasedMerger rankBasedMerger,
                              RelationshipQueryPostActionGenerationProperties relationshipQueryPostActionGenerationProperties,
                              PostActionGenerationProperties postActionGenerationProperties,
                              ObjectProvider<ObjectMapper> objectMapperProvider,
                              OrchestrationProperties orchestrationProperties,
                              ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider,
                              AIEntityConfigurationLoader entityConfigurationLoader,
                              PendingActionStore pendingActionStore,
                              ActionDraftStore actionDraftStore,
                              PromptTemplateResolver promptTemplateResolver,
                              PromptRenderer promptRenderer) {
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.ragProvider = ragProvider;
        this.aiServiceConfig = aiServiceConfig;
        this.orchestrationProperties = orchestrationProperties;
        this.entityConfigurationLoader = entityConfigurationLoader;
        this.pendingActionStore = pendingActionStore;
        this.actionDraftStore = actionDraftStore;
        this.actionBatchSupport = new ActionBatchSupport(actionHandlerRegistry);
        this.actionContextParamResolutionSupport = new ActionContextParamResolutionSupport(actionHandlerRegistry);
        this.postActionGenerationSupport = new PostActionGenerationSupport(
            aiCoreService,
            relationshipQueryPostActionGenerationProperties,
            postActionGenerationProperties,
            objectMapperProvider,
            promptTemplateResolver,
            promptRenderer
        );
        this.ragResponseGenerationSupport = new RagResponseGenerationSupport(
            aiCoreService,
            aiServiceConfig,
            promptTemplateResolver,
            promptRenderer
        );
        this.advancedRagSupport = new AdvancedRagSupport(
            advancedRagProvider,
            aiServiceConfig,
            orchestrationProperties
        );
        this.vectorSpaceSelectionSupport = new VectorSpaceSelectionSupport(
            knowledgeBaseOverviewServiceProvider,
            entityConfigurationLoader,
            vectorSpaceRoutingProperties
        );
        this.informationRagExecutionSupport = new InformationRagExecutionSupport(
            ragProvider,
            advancedRagSupport,
            vectorSpaceRoutingProperties,
            rankBasedMerger,
            ragResponseGenerationSupport
        );
        this.informationAdvancedRagExecutionSupport = new InformationAdvancedRagExecutionSupport(
            advancedRagSupport,
            ragResponseGenerationSupport
        );
        this.confirmationResolutionSupport = new ConfirmationResolutionSupport(
            aiCoreService,
            objectMapperProvider,
            promptTemplateResolver,
            promptRenderer
        );
    }
    
    // =========================================================================
    // PipelineStep Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStepName() {
        return STEP_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return STEP_ORDER;
    }
    
    /**
     * Handle the extracted intents.
     * 
     * <p>Routes single or compound intents to appropriate handlers and
     * builds the orchestration result.</p>
     * 
     * @param context the current pipeline context with intent response
     * @return updated context with intent result
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Handling intent for request {}", context.getRequestId());

        MultiIntentResponse intentResponse = context.getIntentResponse();
        OrchestrationContext orchContext = context.getOrchestrationContext();

        OrchestrationResult result;
        if (intentResponse.getIntents().size() > 1) {
            result = handleCompoundIntents(intentResponse, orchContext, context);
        } else {
            result = handleSingleIntent(intentResponse.getIntents().getFirst(), orchContext, context);
        }

        if (result == null) {
            log.error("Intent handling produced null result for request {}", context.getRequestId());
            result = OrchestrationResult.error("Internal error: intent handling failed");
        }
        
        return context.toBuilder()
            .intentResult(result)
            .build();
    }
    
    // =========================================================================
    // Intent Handling Methods
    // =========================================================================
    
    private OrchestrationResult handleSingleIntent(Intent intent, OrchestrationContext context, PipelineContext pipelineContext) {
        return switch (intent.getType()) {
            case ACTION -> handleAction(intent, context, pipelineContext);
            case INFORMATION -> handleInformation(intent, context, pipelineContext);
            case CONFIRMATION_POSITIVE -> handleConfirmationPositive(context, pipelineContext);
            case CONFIRMATION_NEGATIVE -> handleConfirmationNegative(context, pipelineContext);
            case OUT_OF_SCOPE -> handleOutOfScope(intent);
            default -> OrchestrationResult.error(ERROR_MSG_UNKNOWN_INTENT + intent.getType());
        };
    }
    
    private OrchestrationResult handleAction(Intent intent, OrchestrationContext context, PipelineContext pipelineContext) {
        String actionName = StringUtils.hasText(intent.getAction()) ? intent.getAction() : intent.getIntent();
        if (!StringUtils.hasText(actionName)) {
            return OrchestrationResult.error(ERROR_MSG_MISSING_ACTION_NAME);
        }

        AIActionMetaData meta = getMetadataForAction(actionName);
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        if (policy != null
            && policy.capabilities() != null
            && !policy.capabilities().actionsEnabled()
            && !postActionGenerationSupport.isReadActionAllowedWhenActionsDisabled(
                ReadActionResolutionSupport.isActionExecutionAllowedByPolicy(actionName, meta, policy),
                meta,
                policy
            )) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "ACTIONS_DISABLED_BY_POLICY");
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message("Mutating actions are not enabled in this conversation mode. I can still answer factual questions from configured knowledge and read-only live evidence.")
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        if ((pipelineContext != null
                ? pipelineContext.isAnonymous()
                : context.isAnonymous())
            && (meta == null || !meta.isAnonymousAllowed())) {
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.ACTION_DENIED)
                .success(false)
                .message(ERROR_MSG_ACTION_NOT_PERMITTED_ANON)
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        Optional<AIActionHandler> maybeHandler = actionHandlerRegistry.findHandler(actionName);
        if (maybeHandler.isEmpty()) {
            // Deterministic contract: missing handler is a canonical ERROR with ACTION_NOT_FOUND.
            String message = ERROR_MSG_NO_HANDLER + actionName + "'";
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put(DATA_KEY_ACTION_RESULT, ActionResult.builder()
                .success(false)
                .message(message)
                .errorCode(ERROR_CODE_ACTION_NOT_FOUND)
                .build());
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.ERROR)
                .success(false)
                .message(message)
                .errorCode(ERROR_CODE_ACTION_NOT_FOUND)
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }
        
        AIActionHandler handler = maybeHandler.get();

        Map<String, Object> params = intent.getActionParams();
        String identifier = conversationOwnerIdentifier(context, pipelineContext);

        Map<String, Object> effectiveParams = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();
        ActionContext actionContext = new ActionContext(context, pipelineContext, effectiveParams);
        ResolvedPostActionGeneration postActionRequest = null;

        if (!handler.validateActionAllowed(actionContext)) {
            AIActionMetaData metadata = getMetadataForAction(actionName);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            if (metadata != null) {
                data.put(DATA_KEY_METADATA, publicActionMetadata(metadata));
            }
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.ACTION_DENIED)
                .success(false)
                .message(ERROR_MSG_ACTION_NOT_PERMITTED_USER)
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        postActionRequest = postActionGenerationSupport.resolvePostActionGeneration(
            actionName,
            intent,
            meta,
            policy,
            ReadActionResolutionSupport.isActionExecutionAllowedByPolicy(actionName, meta, policy)
        );

        effectiveParams = actionBatchSupport.applyBatchTargetsDefaulting(meta, effectiveParams, pipelineContext);
        ActionContextParamResolutionSupport.ResolvedActionParams resolvedContextParams =
            actionContextParamResolutionSupport.resolveContextActionParams(meta, effectiveParams, context, pipelineContext);
        effectiveParams = resolvedContextParams.params();
        actionContext = actionContext.withActionParams(effectiveParams);
        if (resolvedContextParams.blockingReadActionResult() != null) {
            return readActionResolutionBlockedResult(
                actionName,
                meta,
                resolvedContextParams.blockingReadActionResult(),
                intent
            );
        }

        boolean confirmedThisRequest = pipelineContext != null && pipelineContext.isActionConfirmed(actionName);
        boolean requiresConfirmation = requiresActionConfirmation(handler);
        if (requiresConfirmation && confirmedThisRequest && hasActionParameter(meta, CONFIRMATION_ACCEPTED_PARAMETER)) {
            Map<String, Object> updated = new LinkedHashMap<>(effectiveParams);
            updated.put(CONFIRMATION_ACCEPTED_PARAMETER, true);
            effectiveParams = updated;
            actionContext = actionContext.withActionParams(effectiveParams);
        }

        OrchestrationProperties.ActionParamProvenanceMode provenanceMode = actionParamProvenanceMode();
        Set<String> trustedResolvedParameters = resolvedContextParams.resolvedParameters();
        if (confirmedThisRequest && requiresConfirmation) {
            trustedResolvedParameters = trustConfirmedActionParams(meta, trustedResolvedParameters, effectiveParams);
        }

        ActionParamValidation validation = validateRequiredActionParams(
            meta,
            effectiveParams,
            pipelineContext,
            trustedResolvedParameters,
            provenanceMode
        );
        validation = suppressConfirmationGateParameter(validation, requiresConfirmation);
        List<String> missingRequired = validation != null ? validation.missingRequired() : List.of();
        if (!missingRequired.isEmpty()) {
            if (context.hasConversation() && actionDraftStore != null) {
                String missingSummary = String.join(", ", missingRequired);
                ActionDraft draft = new ActionDraft(
                    actionName,
                    Collections.unmodifiableMap(new LinkedHashMap<>(effectiveParams)),
                    missingSummary,
                    Instant.now(),
                    Instant.now()
                );
                actionDraftStore.saveDraft(context.getConversationId(), identifier, draft);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            List<String> userMissingRequired = publicMissingRequiredParameters(meta, missingRequired);
            data.put(DATA_KEY_MISSING_REQUIRED_PARAMETERS, List.copyOf(userMissingRequired));
            data.put(DATA_KEY_PROVIDED_PARAMETERS, publicProvidedParameters(meta, effectiveParams));

            String message = userMissingRequired.isEmpty()
                ? "This action needs storefront session context before it can proceed. Please reopen the assistant and try again."
                : "To proceed, please provide: " + String.join(", ", userMissingRequired) + ".";
            List<NextStepRecommendation> nextSteps = new ArrayList<>(extractNextSteps(intent));
            if (!userMissingRequired.isEmpty()) {
                nextSteps.add(NextStepRecommendation.builder()
                    .intent("provide_missing_action_params")
                    .query("Please provide: " + String.join(", ", userMissingRequired) + ".")
                    .rationale("These parameters are required to execute the requested action.")
                    .confidence(1.0d)
                    .build());
            }
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message(message)
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .data(Collections.unmodifiableMap(data))
                .nextSteps(Collections.unmodifiableList(nextSteps))
                .build();
        }

        ActionExecutableValidation executableValidation = validateExecutableActionParams(
            handler.actionRuntimeConfig(),
            meta,
            effectiveParams,
            buildEvidenceBundle(pipelineContext),
            resolvedContextParams.resolvedParameters()
        );
        validation = mergeExecutableValidation(validation, executableValidation);
        if (executableValidation != null && executableValidation.hasFailures()) {
            if (context.hasConversation() && actionDraftStore != null) {
                String missingSummary = String.join(", ", executableValidation.publicMissing());
                ActionDraft draft = new ActionDraft(
                    actionName,
                    Collections.unmodifiableMap(new LinkedHashMap<>(effectiveParams)),
                    StringUtils.hasText(missingSummary) ? missingSummary : "trusted action target",
                    Instant.now(),
                    Instant.now()
                );
                actionDraftStore.saveDraft(context.getConversationId(), identifier, draft);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put("blockedReason", "ACTION_ARGUMENTS_NOT_EXECUTABLE");
            data.put(DATA_KEY_MISSING_REQUIRED_PARAMETERS, executableValidation.publicMissing());
            data.put(DATA_KEY_PROVIDED_PARAMETERS, publicProvidedParameters(meta, effectiveParams));
            data.put(DATA_KEY_METADATA, publicActionMetadata(getMetadataForAction(actionName)));

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message(actionExecutableValidationMessage(executableValidation))
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        // Confirmation message is only meaningful for confirmable actions.
        // For safe actions, expose a deterministic execution indicator (used by tests/UI).
        String confirmationMessage = null;
        if (requiresConfirmation) {
            // Never let confirmation-message formatting crash the pipeline.
            // Some handlers validate required params inside getConfirmationMessage().
            try {
                confirmationMessage = handler.getConfirmationMessage(effectiveParams, actionContext);
            } catch (Exception ex) {
                log.debug("Action handler {} failed to build confirmation message for '{}': {}",
                    handler.getClass().getName(), actionName, ex.getMessage());
            }
        } else {
            confirmationMessage = "Executing " + actionName;
        }

        if (isSpecialistWriteRequest(meta, pipelineContext)) {
            if (!canSpecialistProposeWrite(meta, pipelineContext)) {
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ACTION_DENIED)
                    .success(false)
                    .message(
                        "The write action is not available in the effective specialist profile."
                    )
                    .errorCode("ACTION_NOT_IN_EFFECTIVE_PROFILE")
                    .data(Map.of(DATA_KEY_ACTION, actionName))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            if (!requiresConfirmation) {
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ACTION_DENIED)
                    .success(false)
                    .message(
                        "Specialist write proposals require application-owned confirmation."
                    )
                    .errorCode("SPECIALIST_WRITE_CONFIRMATION_REQUIRED")
                    .data(Map.of(DATA_KEY_ACTION, actionName))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put(DATA_KEY_CONFIRMATION_MESSAGE, confirmationMessage);
            data.put(DATA_KEY_CONFIRMATION_REQUIRED, true);
            data.put(
                DATA_KEY_METADATA,
                publicActionMetadata(getMetadataForAction(actionName))
            );
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
                .success(false)
                .message(
                    StringUtils.hasText(confirmationMessage)
                        ? confirmationMessage
                        : "Please confirm to proceed."
                )
                .data(Collections.unmodifiableMap(data))
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .nextSteps(extractNextSteps(intent))
                .actionProposalCandidate(new ActionProposalCandidate(
                    actionName,
                    effectiveParams,
                    actionContext
                ))
                .build();
        }

        if (requiresConfirmation && !confirmedThisRequest && context.hasConversation()) {
            // Loop breaker: if the LLM keeps re-issuing the same ACTION instead of emitting
            // CONFIRMATION_POSITIVE/NEGATIVE, resolve confirmation using a dedicated LLM prompt
            // (no backend string matching) and execute/cancel accordingly.
            OrchestrationResult resolved = maybeResolvePendingConfirmationForMisclassifiedAction(
                actionName,
                effectiveParams,
                confirmationMessage,
                context,
                pipelineContext
            );
            if (resolved != null) {
                return resolved;
            }

            if (context.hasConversation() && actionDraftStore != null) {
                actionDraftStore.clearDrafts(context.getConversationId(), identifier);
            }

            EvidenceBundle evidence = buildEvidenceBundle(pipelineContext);
            PendingAction pending = new PendingAction(
                actionName,
                Collections.unmodifiableMap(new LinkedHashMap<>(effectiveParams)),
                confirmationMessage,
                Instant.now(),
                ActionEvidenceSupport.pendingTrustedEvidenceValues(
                    evidence,
                    meta,
                    effectiveParams,
                    resolvedContextParams.resolvedParameters()
                )
            );
            if (pendingActionStore != null) {
                pendingActionStore.pushPendingAction(context.getConversationId(), identifier, pending);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put(DATA_KEY_CONFIRMATION_MESSAGE, confirmationMessage);
            data.put(DATA_KEY_CONFIRMATION_REQUIRED, true);
            data.put(DATA_KEY_PROVIDED_PARAMETERS, publicProvidedParameters(meta, effectiveParams));
            data.put(DATA_KEY_METADATA, publicActionMetadata(getMetadataForAction(actionName)));

            String message = StringUtils.hasText(confirmationMessage)
                ? confirmationMessage
                : "Please confirm to proceed.";

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
                .success(false)
                .message(message)
                .data(Collections.unmodifiableMap(data))
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        try {
            if (context.hasConversation() && actionDraftStore != null) {
                actionDraftStore.clearDrafts(context.getConversationId(), identifier);
            }
            ActionConfirmationState confirmationState =
                !requiresConfirmation || confirmedThisRequest || !context.hasConversation()
                ? ActionConfirmationState.CONFIRMED
                : ActionConfirmationState.NOT_CONFIRMED;
            GovernedActionInvocationOutcome invocationOutcome =
                new DefaultGovernedActionInvocationService(actionHandlerRegistry).invoke(
                    GovernedActionInvocationSupport.invocation(
                        actionName,
                        effectiveParams,
                        actionContext,
                        actionHandlerRegistry,
                        confirmationState,
                        List.of()
                    )
                );
            if (invocationOutcome.status() == GovernedActionInvocationStatus.CONFIRMATION_REQUIRED) {
                Map<String, Object> confirmationData = new LinkedHashMap<>();
                confirmationData.put(DATA_KEY_ACTION, actionName);
                confirmationData.put(DATA_KEY_CONFIRMATION_MESSAGE,
                    invocationOutcome.actionResult().getMessage());
                confirmationData.put(DATA_KEY_CONFIRMATION_REQUIRED, true);
                confirmationData.put(DATA_KEY_PROVIDED_PARAMETERS,
                    publicProvidedParameters(meta, effectiveParams));
                confirmationData.put(DATA_KEY_METADATA,
                    publicActionMetadata(getMetadataForAction(actionName)));
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.CONFIRMATION_REQUIRED)
                    .success(false)
                    .message(invocationOutcome.actionResult().getMessage())
                    .data(Collections.unmodifiableMap(confirmationData))
                    .metadata(publicActionParamValidationMetadata(meta, validation))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            if (invocationOutcome.status() == GovernedActionInvocationStatus.DENIED) {
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ACTION_DENIED)
                    .success(false)
                    .message(invocationOutcome.publicFailure().publicMessage())
                    .errorCode(invocationOutcome.publicFailure().reason())
                    .data(Map.of(DATA_KEY_ACTION, actionName))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            if (invocationOutcome.status() == GovernedActionInvocationStatus.INVALID) {
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message(invocationOutcome.publicFailure().publicMessage())
                    .errorCode(invocationOutcome.publicFailure().reason())
                    .data(Map.of(
                        DATA_KEY_ACTION, actionName,
                        DATA_KEY_ACTION_RESULT, invocationOutcome.actionResult()
                    ))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            if (invocationOutcome.status() == GovernedActionInvocationStatus.FAILED) {
                ActionResult failedResult = invocationOutcome.actionResult();
                Map<String, Object> failureData = new LinkedHashMap<>();
                failureData.put(DATA_KEY_ACTION, actionName);
                if (failedResult != null) {
                    failureData.put(DATA_KEY_ACTION_RESULT, failedResult);
                }
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message(invocationOutcome.publicFailure().publicMessage())
                    .errorCode(invocationOutcome.publicFailure().reason())
                    .data(Collections.unmodifiableMap(failureData))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            if (invocationOutcome.status()
                == GovernedActionInvocationStatus.OUTCOME_UNKNOWN) {
                ActionResult unknownResult = invocationOutcome.actionResult();
                Map<String, Object> unknownData = new LinkedHashMap<>();
                unknownData.put(DATA_KEY_ACTION, actionName);
                if (unknownResult != null) {
                    unknownData.put(DATA_KEY_ACTION_RESULT, unknownResult);
                }
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message(invocationOutcome.publicFailure().publicMessage())
                    .errorCode("ACTION_OUTCOME_UNKNOWN")
                    .data(Collections.unmodifiableMap(unknownData))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
            ActionResult actionResult = invocationOutcome.actionResult();
            boolean success = actionResult != null && actionResult.isSuccess();
            
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put(DATA_KEY_CONFIRMATION_MESSAGE, confirmationMessage);
            data.put(DATA_KEY_METADATA, publicActionMetadata(getMetadataForAction(actionName)));
            if (actionResult != null) {
                data.put(DATA_KEY_ACTION_RESULT, actionResult);
            }

            OrchestrationResult readFallback = maybeFallbackReadActionToRag(intent, meta, actionResult, context, pipelineContext);
            if (readFallback != null) {
                return readFallback;
            }

            String message = actionResult != null ? actionResult.getMessage() : null;
            Map<String, Object> resultData = Collections.unmodifiableMap(data);

            PostActionGenerationOutcome postActionGeneration = postActionGenerationSupport.maybeGeneratePostActionSummary(
                actionName,
                handler,
                intent,
                actionResult,
                context,
                pipelineContext,
                effectiveParams,
                postActionRequest
            );
            if (postActionGeneration != null) {
                message = postActionGeneration.message();
                Map<String, Object> enriched = new LinkedHashMap<>(data);
                enriched.put("postActionGeneration", postActionGeneration.metadata());
                if (postActionGeneration.summary() != null) {
                    enriched.put("summary", postActionGeneration.summary());
                    enriched.put(DATA_KEY_ANSWER, postActionGeneration.summary());
                }
                resultData = Collections.unmodifiableMap(enriched);
            }

            enqueuePostPolicies(actionName, effectiveParams, actionResult, actionContext);

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.ACTION_EXECUTED)
                .success(success)
                .message(message)
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .data(resultData)
                .nextSteps(extractNextSteps(intent))
                .build();
        } catch (Exception ex) {
            log.error("Action handler {} threw exception executing '{}': {}", 
                handler.getClass().getName(), actionName, ex.getMessage(), ex);
            ActionResult errorResult = handler.handleError(ex, actionContext);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ACTION, actionName);
            data.put(DATA_KEY_METADATA, publicActionMetadata(getMetadataForAction(actionName)));
            if (errorResult != null) {
                data.put(DATA_KEY_ACTION_RESULT, errorResult);
            }
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.ERROR)
                .success(false)
                .message(errorResult != null ? errorResult.getMessage() : ex.getMessage())
                .metadata(publicActionParamValidationMetadata(meta, validation))
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }
    }

    private OrchestrationProperties.ActionParamProvenanceMode actionParamProvenanceMode() {
        return orchestrationProperties != null && orchestrationProperties.getActionParamProvenanceMode() != null
            ? orchestrationProperties.getActionParamProvenanceMode()
            : OrchestrationProperties.ActionParamProvenanceMode.WARN;
    }

    private Set<String> trustConfirmedActionParams(AIActionMetaData meta,
                                                   Set<String> trustedResolvedParameters,
                                                   Map<String, Object> effectiveParams) {
        java.util.LinkedHashSet<String> trusted = new java.util.LinkedHashSet<>();
        if (trustedResolvedParameters != null && !trustedResolvedParameters.isEmpty()) {
            trusted.addAll(trustedResolvedParameters);
        }
        if (effectiveParams != null && !effectiveParams.isEmpty()) {
            for (Map.Entry<String, Object> entry : effectiveParams.entrySet()) {
                if (entry == null || !StringUtils.hasText(entry.getKey())) {
                    continue;
                }
                String parameter = entry.getKey().trim();
                if (!ActionParameterSupport.isUserVisibleActionParameter(meta, parameter)) {
                    continue;
                }
                AIActionParamSchema schema = ActionParameterSupport.paramSchema(meta, parameter);
                if (schema != null && Boolean.TRUE.equals(schema.getEvidenceBound())) {
                    continue;
                }
                if (ActionValueSupport.hasMeaningfulJavaValue(entry.getValue())) {
                    trusted.add(parameter);
                }
            }
        }
        return trusted.isEmpty() ? Set.of() : Collections.unmodifiableSet(trusted);
    }

    private void enqueuePostPolicies(String actionName,
                                     Map<String, Object> effectiveParams,
                                     ActionResult actionResult,
                                     ActionContext actionContext) {
        ActionPostPolicyEngine engine = actionPostPolicyEngineProvider != null
            ? actionPostPolicyEngineProvider.getIfAvailable()
            : null;
        if (engine == null || !StringUtils.hasText(actionName) || actionResult == null || !actionResult.isSuccess()) {
            return;
        }
        try {
            engine.handleSuccessfulAction(actionName, effectiveParams, actionResult, actionContext);
        } catch (Exception ex) {
            log.warn("Post-action policy enqueue failed for action '{}': {}", actionName, ex.getMessage(), ex);
        }
    }

    private OrchestrationResult readActionResolutionBlockedResult(String actionName,
                                                                  AIActionMetaData actionMeta,
                                                                  ActionContextParamResolutionSupport.BlockingReadActionResult blocking,
                                                                  Intent intent) {
        ActionResult actionResult = blocking.result();
        String readActionName = blocking.actionName();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ACTION, readActionName);
        data.put("dependentAction", actionName);
        AIActionMetaData readMeta = null;
        Optional<AIActionMetaData> readMetaOptional = actionHandlerRegistry.findMetadata(readActionName);
        if (readMetaOptional != null && readMetaOptional.isPresent()) {
            readMeta = readMetaOptional.get();
        }
        data.put(DATA_KEY_METADATA, publicActionMetadata(readMeta != null ? readMeta : actionMeta));
        if (actionResult != null) {
            data.put(DATA_KEY_ACTION_RESULT, actionResult);
        }
        String message = actionResult != null && StringUtils.hasText(actionResult.getMessage())
            ? actionResult.getMessage()
            : "The required read action could not resolve the action context.";
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(false)
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    /**
     * For READ actions, treat the handler as a "helper tool": if it returns an empty successful payload,
     * run RAG (+ generation when enabled) and return that result instead of the action output.
     */
    private OrchestrationResult maybeFallbackReadActionToRag(Intent intent,
                                                            AIActionMetaData meta,
                                                            ActionResult actionResult,
                                                            OrchestrationContext context,
                                                            PipelineContext pipelineContext) {
        if (meta == null || meta.getAccessMode() != ActionAccessMode.READ) {
            return null;
        }
        // In action-first modes, an empty list is a valid, user-visible result.
        // Falling back to RAG here makes it look like the action wasn't executed.
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        if (policy != null
            && policy.capabilities() != null
            && policy.capabilities().actionsPreferred()) {
            return null;
        }
        if (actionResult == null || !actionResult.isSuccess()) {
            return null;
        }
        if (!RagResultSummarySupport.isEmptyActionResultPayload(actionResult.getData())) {
            return null;
        }
        if (ragProvider == null || ragProvider.getIfAvailable() == null) {
            return null;
        }

        List<String> vectorSpaces = RagContextSupport.parseVectorSpaces(intent != null ? intent.getVectorSpace() : null);
        if (vectorSpaces.isEmpty()) {
            vectorSpaces = vectorSpaceSelectionSupport.resolveAllVectorSpaces();
        }
        if (vectorSpaces.isEmpty()) {
            return null;
        }

        boolean generationEnabled = aiServiceConfig == null
            || aiServiceConfig.getFeatures() == null
            || Boolean.TRUE.equals(aiServiceConfig.getFeatures().getEnableGeneration());

        Intent infoIntent = new Intent();
        infoIntent.setType(ai.fabric.dto.IntentType.INFORMATION);
        infoIntent.setRequiresRetrieval(true);
        infoIntent.setRequiresGeneration(generationEnabled);

        String query = intent != null && StringUtils.hasText(intent.getOptimizedQuery())
            ? intent.getOptimizedQuery()
            : (pipelineContext != null ? pipelineContext.getEffectiveQuery() : null);
        if (StringUtils.hasText(query)) {
            infoIntent.setOptimizedQuery(query);
            infoIntent.setIntent(query);
        }
        infoIntent.setVectorSpace(String.join(",", vectorSpaces));

        OrchestrationResult result = handleInformation(infoIntent, context, pipelineContext);
        if (result == null) {
            return null;
        }

        boolean exposeProbe = policy != null
            && policy.capabilities() != null
            && policy.capabilities().exposeReadProbeFallbackAttempt();

        if (exposeProbe) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("action", meta.getName());
            if (meta.getAccessMode() != null) {
                probe.put("accessMode", meta.getAccessMode().name());
            }
            probe.put("success", true);
            probe.put("emptyPayload", true);
            if (StringUtils.hasText(actionResult.getMessage())) {
                probe.put("message", actionResult.getMessage());
            }
            probe.put("fallbackToRag", true);

            Map<String, Object> merged = new LinkedHashMap<>();
            if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
                merged.putAll(result.getMetadata());
            }
            merged.put("readProbe", Collections.unmodifiableMap(probe));
            result.setMetadata(Collections.unmodifiableMap(merged));
        }

        return result;
    }

    private boolean requiresActionConfirmation(AIActionHandler handler) {
        if (handler == null) {
            return false;
        }
        return handler.requiresConfirmation();
    }

    private boolean isSpecialistWriteRequest(
        AIActionMetaData metadata,
        PipelineContext pipelineContext
    ) {
        return metadata != null
            && metadata.getAccessMode() != null
            && !metadata.getAccessMode().isReadOnly()
            && pipelineContext != null
            && pipelineContext.getOrchestrationRequest() != null
            && pipelineContext.getOrchestrationRequest().purpose()
                == OrchestrationRequestPurpose.SPECIALIST;
    }

    private boolean canSpecialistProposeWrite(
        AIActionMetaData metadata,
        PipelineContext pipelineContext
    ) {
        return pipelineContext.getEffectiveCapabilityProfile() != null
            && pipelineContext.getEffectiveCapabilityProfile()
                .canProposeWriteAction(metadata.getName());
    }

    private OrchestrationResult handleConfirmationPositive(OrchestrationContext context, PipelineContext pipelineContext) {
        if (context == null || !context.hasConversation() || pendingActionStore == null) {
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("There is no pending action to confirm.")
                .build();
        }

        OrchestrationResult intercepted = maybeHandleConfiguredConfirmationInterception(
            ConfirmationResolutionDecision.POSITIVE,
            context,
            pipelineContext
        );
        if (intercepted != null) {
            return intercepted;
        }

        String ownerId = conversationOwnerIdentifier(context, pipelineContext);
        PendingAction pending = pendingActionStore.popPendingAction(
            context.getConversationId(),
            ownerId
        ).orElse(null);
        if (pending == null) {
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("There is no pending action to confirm.")
                .build();
        }
        if (actionDraftStore != null) {
            actionDraftStore.clearDrafts(context.getConversationId(), ownerId);
        }

        Intent synthetic = Intent.builder()
            .type(ai.fabric.dto.IntentType.ACTION)
            .action(pending.action())
            .actionParams(pending.actionParams() != null ? pending.actionParams() : Map.of())
            .build();

        PipelineContext base = pipelineContext != null ? pipelineContext : PipelineContext.from("confirm", context);
        PipelineContext marked = base.toBuilder()
            .confirmedActions(java.util.Set.of(pending.action()))
            .metadata(ActionEvidenceSupport.mergeTrustedActionEvidence(base.getMetadata(), pending.trustedEvidenceValuesByKey()))
            .build();

        return handleAction(synthetic, context, marked);
    }

    private OrchestrationResult handleConfirmationNegative(OrchestrationContext context, PipelineContext pipelineContext) {
        if (context == null || !context.hasConversation() || pendingActionStore == null) {
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Okay —  All sorted, You do not need to do any further action.")
                .build();
        }

        OrchestrationResult intercepted = maybeHandleConfiguredConfirmationInterception(
            ConfirmationResolutionDecision.NEGATIVE,
            context,
            pipelineContext
        );
        if (intercepted != null) {
            return intercepted;
        }

        String ownerId = conversationOwnerIdentifier(context, pipelineContext);
        pendingActionStore.popPendingAction(context.getConversationId(), ownerId);
        if (actionDraftStore != null) {
            actionDraftStore.clearDrafts(context.getConversationId(), ownerId);
        }
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Okay —  All sorted, You do not need to do any further action.")
            .build();
    }

    private OrchestrationResult maybeHandleConfiguredConfirmationInterception(ConfirmationResolutionDecision decision,
                                                                              OrchestrationContext context,
                                                                              PipelineContext pipelineContext) {
        if (decision == null
            || context == null
            || !context.hasConversation()
            || pendingActionStore == null) {
            return null;
        }

        List<ConfirmationInterceptorRule> rules =
            ConfiguredConfirmationSupport.configuredConfirmationInterceptorRules(confirmationInterceptorCatalogProvider);
        if (rules.isEmpty()) {
            return null;
        }

        String conversationId = context.getConversationId();
        String ownerId = conversationOwnerIdentifier(context, pipelineContext);
        List<PendingAction> stackSnapshot = pendingActionStore.getPendingActionStack(conversationId, ownerId);
        if (stackSnapshot == null || stackSnapshot.isEmpty()) {
            return null;
        }

        PendingAction pending = stackSnapshot.getFirst();
        ConfirmationInterceptorRule rule =
            ConfiguredConfirmationSupport.findMatchingConfiguredConfirmationRule(rules, pending, decision);
        if (rule == null || rule.decision() == null || rule.decision().type() == null) {
            return null;
        }

        List<PendingAction> workingStack = new ArrayList<>(stackSnapshot);
        String onceParam = rule.trigger() != null
            ? ConfirmationInterceptorParamSupport.normalizeOnceParam(rule.trigger().onceParam())
            : null;
        if (StringUtils.hasText(onceParam) && !workingStack.isEmpty()) {
            workingStack.set(0, ConfiguredConfirmationSupport.withBooleanPendingParam(workingStack.getFirst(), onceParam, true));
        }

        ConfirmationInterceptorDecision configuredDecision = rule.decision();
        Map<String, Object> resolvedParams =
            ConfiguredConfirmationSupport.resolveConfiguredConfirmationActionParams(configuredDecision.params(), stackSnapshot);
        ConfiguredConfirmationSupport.applyConfiguredConfirmationStackPolicy(rule.stackPolicy(), workingStack);
        pendingActionStore.replacePendingActionStack(conversationId, ownerId, workingStack);

        if (configuredDecision.type() == ConfirmationInterceptorDecisionType.REPLY) {
            Object resolvedMessage =
                ConfiguredConfirmationSupport.resolveConfiguredConfirmationTemplateValue(configuredDecision.message(), stackSnapshot);
            String message = resolvedMessage != null ? String.valueOf(resolvedMessage) : "";
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message(message)
                .build();
        }

        if (!StringUtils.hasText(configuredDecision.action())) {
            return null;
        }

        Intent synthetic = Intent.builder()
            .type(ai.fabric.dto.IntentType.ACTION)
            .action(configuredDecision.action().trim())
            .actionParams(resolvedParams)
            .confidence(1.0d)
            .build();

        PipelineContext effectiveContext = pipelineContext;
        if (configuredDecision.type() == ConfirmationInterceptorDecisionType.EXECUTE_ACTION) {
            Set<String> confirmed = new java.util.LinkedHashSet<>();
            if (pipelineContext != null && pipelineContext.getConfirmedActions() != null) {
                confirmed.addAll(pipelineContext.getConfirmedActions());
            }
            confirmed.add(configuredDecision.action().trim());
            effectiveContext = pipelineContext != null
                ? pipelineContext.toBuilder().confirmedActions(Set.copyOf(confirmed)).build()
                : pipelineContext;
        }

        return handleAction(synthetic, context, effectiveContext);
    }

    /**
     * If there is a pending action, and the LLM emitted the same ACTION again, try to interpret the user's
     * message as confirmation/rejection using a dedicated confirmation prompt.
     *
     * <p>This avoids brittle backend string-matching ("yes/no") while preventing repeated CONFIRMATION_REQUIRED loops.</p>
     */
    private OrchestrationResult maybeResolvePendingConfirmationForMisclassifiedAction(String actionName,
                                                                                      Map<String, Object> effectiveParams,
                                                                                      String confirmationMessage,
                                                                                      OrchestrationContext context,
                                                                                      PipelineContext pipelineContext) {
        if (!StringUtils.hasText(actionName)
            || context == null
            || !context.hasConversation()
            || pendingActionStore == null
            || confirmationResolutionSupport == null) {
            return null;
        }

        PendingAction pending;
        try {
            pending = pendingActionStore.peekPendingAction(
                context.getConversationId(),
                conversationOwnerIdentifier(context, pipelineContext)
            ).orElse(null);
        } catch (Exception ex) {
            return null;
        }
        if (pending == null || !StringUtils.hasText(pending.action())) {
            return null;
        }
        if (!actionName.trim().equalsIgnoreCase(pending.action().trim())) {
            return null;
        }

        Map<String, Object> pendingParams = pending.actionParams() != null ? pending.actionParams() : Map.of();
        Map<String, Object> currentParams = effectiveParams != null ? effectiveParams : Map.of();
        if (!ConfirmationDecisionSupport.actionParamsEquivalentOrSubset(currentParams, pendingParams)) {
            // The user might be adjusting params rather than confirming; do not force-confirm.
            return null;
        }

        // Use the original user message, never the processed query (which may include injected context blocks).
        String userMessage = pipelineContext != null ? pipelineContext.getOriginalQuery() : null;
        if (!StringUtils.hasText(userMessage)) {
            return null;
        }

        ConfirmationResolutionOutcome outcome = confirmationResolutionSupport.resolve(
            pending.action(),
            StringUtils.hasText(pending.description()) ? pending.description() : confirmationMessage,
            userMessage,
            context
        );
        if (outcome == null || outcome.decision() == null || outcome.decision() == ConfirmationResolutionDecision.UNKNOWN) {
            return null;
        }

        OrchestrationResult resolved;
        if (outcome.decision() == ConfirmationResolutionDecision.POSITIVE) {
            resolved = handleConfirmationPositive(context, pipelineContext);
        } else {
            resolved = handleConfirmationNegative(context, pipelineContext);
        }

        if (resolved == null) {
            return null;
        }

        Map<String, Object> merged = new LinkedHashMap<>(resolved.getMetadata() != null ? resolved.getMetadata() : Map.of());
        merged.put("pendingActionResolution", outcome.debugMetadata() != null ? outcome.debugMetadata() : Map.of());
        resolved.setMetadata(Collections.unmodifiableMap(merged));
        return resolved;
    }

    private OrchestrationResult handleInformation(Intent intent, OrchestrationContext context, PipelineContext pipelineContext) {
        InformationIntentPlanningSupport.Plan informationPlan = InformationIntentPlanningSupport.plan(
            intent,
            context,
            pipelineContext,
            orchestrationProperties,
            isDeterministicInformationMode(pipelineContext)
        );

        boolean retrievalEnabled = informationPlan.retrievalEnabled();
        OrchestrationPolicy.RagBudgets ragBudgets = informationPlan.ragBudgets();
        boolean deepRetrievalEnabled = informationPlan.deepRetrievalEnabled();
        boolean retrievalAllowlistRequired = informationPlan.retrievalAllowlistRequired();
        boolean vectorSpaceSelectionRequired = informationPlan.vectorSpaceSelectionRequired();
        boolean fanoutAllowed = informationPlan.fanoutAllowed();
        boolean deterministic = informationPlan.deterministic();
        boolean requiresRetrieval = informationPlan.requiresRetrieval();
        boolean needsGeneration = informationPlan.needsGeneration();
        String optimizedQuery = informationPlan.optimizedQuery();
        String processedQuery = informationPlan.processedQuery();
        String retrievalBaseQuery = informationPlan.retrievalBaseQuery();
        String generationQuery = informationPlan.generationQuery();
        Map<String, Object> metadata = informationPlan.metadata();
        boolean forceRetrievalWhenTargetsPresent = informationPlan.forceRetrievalWhenTargetsPresent();

        if (requiresRetrieval && !retrievalEnabled) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "RETRIEVAL_DISABLED_BY_POLICY");
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message("Retrieval is disabled by server policy for this request.")
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        if (requiresRetrieval
            && retrievalAllowlistRequired
            && (ragBudgets == null || !ragBudgets.hasVectorSpaceAllowlist())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("reason", "RETRIEVAL_ALLOWLIST_REQUIRED");
            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message("Retrieval requires a configured vectorSpace allowlist in this mode.")
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        ReadActionResolutionService.ResolutionOutcome readActionResolution = null;

        if (!requiresRetrieval) {
            if (!needsGeneration) {
                if (hasPendingAction(context, pipelineContext)) {
                    return OrchestrationResult.builder()
                        .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                        .success(false)
                        .message("Please confirm or reject the pending action.")
                        .build();
                }
                readActionResolution = ReadActionResolutionSupport.resolve(
                    readActionResolutionServiceProvider,
                    intent,
                    context,
                    pipelineContext,
                    metadata
                );
                if (readActionResolution.attempted()
                    && (readActionResolution.hasGroundingEvidence() || readActionResolution.useRag())) {
                    needsGeneration = true;
                    metadata.put("readActionResolutionForcedGeneration", true);
                    metadata.put(DATA_KEY_REQUIRES_GENERATION, true);
                }
                if (readActionResolution.canAnswerFromActionEvidenceOnly()) {
                    return handleInformationFromReadActionEvidence(
                        intent,
                        pipelineContext,
                        generationQuery,
                        metadata,
                        readActionResolution
                    );
                }
                if (readActionResolution.attempted() && readActionResolution.useRag()) {
                    requiresRetrieval = true;
                    metadata.put("readActionResolutionForcedRetrieval", true);
                    metadata.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
                    metadata.put("requiresRetrieval", true);
                }
                if (!requiresRetrieval && !needsGeneration) {
                    OrchestrationResult direct = handleInformationDirectAnswer(intent, context);
                    return ReadActionResolutionSupport.attachDiagnostics(direct, readActionResolution);
                }
            }
        }

        if (readActionResolution == null) {
            readActionResolution = ReadActionResolutionSupport.resolve(
                readActionResolutionServiceProvider,
                intent,
                context,
                pipelineContext,
                metadata
            );
        }
        if (!needsGeneration
            && readActionResolution.attempted()
            && (readActionResolution.hasGroundingEvidence() || readActionResolution.useRag())) {
            needsGeneration = true;
            metadata.put("readActionResolutionForcedGeneration", true);
            metadata.put(DATA_KEY_REQUIRES_GENERATION, true);
        }
        if (readActionResolution.canAnswerFromActionEvidenceOnly()) {
            return handleInformationFromReadActionEvidence(
                intent,
                pipelineContext,
                generationQuery,
                metadata,
                readActionResolution
            );
        }
        if (readActionResolution.attempted() && readActionResolution.useRag() && !requiresRetrieval) {
            requiresRetrieval = true;
            metadata.put("readActionResolutionForcedRetrieval", true);
            metadata.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
            metadata.put("requiresRetrieval", true);
        }
        if (!requiresRetrieval) {
            OrchestrationResult result = handleInformationGenerationOnly(intent, pipelineContext, generationQuery, metadata);
            return ReadActionResolutionSupport.attachDiagnostics(result, readActionResolution);
        }

        InformationVectorSpaceRoutingSupport.RoutedVectorSpaces routedVectorSpaces =
            InformationVectorSpaceRoutingSupport.route(
                intent,
                metadata,
                ragBudgets,
                vectorSpaceSelectionRequired,
                deterministic,
                fanoutAllowed,
                readActionResolution,
                entityConfigurationLoader,
                vectorSpaceSelectionSupport
            );
        if (routedVectorSpaces.terminalResult() != null) {
            return routedVectorSpaces.terminalResult();
        }
        List<String> vectorSpaces = routedVectorSpaces.vectorSpaces();

        InformationRetrievalQuerySupport.PreparedRetrievalQuery preparedQuery =
            InformationRetrievalQuerySupport.prepare(
                intent,
                pipelineContext,
                orchestrationProperties,
                deepRetrievalEnabled,
                forceRetrievalWhenTargetsPresent,
                optimizedQuery,
                processedQuery,
                retrievalBaseQuery,
                generationQuery,
                metadata
            );
        String retrievalQuery = preparedQuery.retrievalQuery();
        EmbeddingQueryComposer.Result embedding = preparedQuery.embedding();

        if (vectorSpaces.size() > 1) {
            OrchestrationResult result = informationRagExecutionSupport.fanOut(
                intent,
                context,
                pipelineContext,
                deterministic,
                needsGeneration,
                generationQuery,
                retrievalQuery,
                metadata,
                vectorSpaces,
                ragBudgets,
                readActionResolution
            );
            return ReadActionResolutionSupport.attachDiagnostics(result, readActionResolution);
        }

        String advancedDecisionQuery = preparedQuery.advancedDecisionQuery();

        if (advancedRagSupport.shouldUseAdvancedRag(intent, needsGeneration, advancedDecisionQuery, context, pipelineContext)) {
            String advancedQuery = embedding != null && StringUtils.hasText(embedding.embeddingQuery())
                ? embedding.embeddingQuery()
                : retrievalQuery;
            OrchestrationResult advanced = informationAdvancedRagExecutionSupport.execute(
                    intent,
                    context,
                    pipelineContext,
                    needsGeneration,
                    generationQuery,
                    advancedQuery,
                    metadata,
                    readActionResolution
                );
            if (advanced != null) {
                if (deepRetrievalEnabled
                    && fanoutAllowed
                    && RagResultSummarySupport.isNoEvidenceRagResult(
                        advanced,
                        DATA_KEY_DOCUMENTS,
                        DATA_KEY_RAG_RESPONSE,
                        DATA_KEY_CONFIDENCE_SCORE,
                        RAG_NO_CONTEXT_MESSAGE
                    )) {
                    List<String> fallbackSpaces = vectorSpaceSelectionSupport.resolveAllVectorSpaces();
                    fallbackSpaces = vectorSpaceSelectionSupport.capVectorSpacesToBudget(fallbackSpaces, ragBudgets);
                    if (fallbackSpaces.size() > 1) {
                        // Deep mode: when Advanced RAG returns no documents/confidence, broaden retrieval across
                        // all configured spaces instead of returning a potentially
                        // ungrounded generated answer.
                        metadata.put("advancedRagFallback", true);
                        metadata.put("advancedRagFallbackReason", "NO_RELEVANT_RESULTS");

                        vectorSpaces = fallbackSpaces;
                        intent.setVectorSpace(String.join(",", vectorSpaces));
                        metadata.put("retrievalStrategy", "FAN_OUT");
                        metadata.put("vectorSpacesSelected", vectorSpaces);
                        metadata.put("vectorSpacesSelectionSource", "ADVANCED_FALLBACK_FAN_OUT");

                        return informationRagExecutionSupport.fanOut(
                            intent,
                            context,
                            pipelineContext,
                            deterministic,
                            needsGeneration,
                            generationQuery,
                            retrievalQuery,
                            metadata,
                            vectorSpaces,
                            ragBudgets,
                            readActionResolution
                        );
                    }
                }
                return ReadActionResolutionSupport.attachDiagnostics(advanced, readActionResolution);
            }
        }

        OrchestrationResult result = informationRagExecutionSupport.basic(
            intent,
            context,
            pipelineContext,
            needsGeneration,
            generationQuery,
            retrievalQuery,
            metadata,
            ragBudgets,
            readActionResolution
        );
        return ReadActionResolutionSupport.attachDiagnostics(result, readActionResolution);
    }

    // Retrieval queries must always be derived from the user's actual query (PII-processed if enabled),
    // never from any carrier string that mixes history/attachments into the query.

    private boolean hasPendingAction(
        OrchestrationContext context,
        PipelineContext pipelineContext
    ) {
        if (context == null || !context.hasConversation() || pendingActionStore == null) {
            return false;
        }
        try {
            return pendingActionStore.peekPendingAction(
                context.getConversationId(),
                conversationOwnerIdentifier(context, pipelineContext)
            ).isPresent();
        } catch (Exception ex) {
            return false;
        }
    }

    private String conversationOwnerIdentifier(
        OrchestrationContext context,
        PipelineContext pipelineContext
    ) {
        if (pipelineContext != null
            && StringUtils.hasText(
                pipelineContext.getConversationOwnerIdentifier()
            )) {
            return pipelineContext.getConversationOwnerIdentifier();
        }
        return context != null ? context.getIdentifier() : null;
    }

    private OrchestrationResult handleInformationDirectAnswer(Intent intent,
                                                             OrchestrationContext context) {
        return InformationGenerationResponseSupport.directAnswer(intent, context);
    }

    private OrchestrationResult handleInformationGenerationOnly(Intent intent,
                                                                PipelineContext pipelineContext,
                                                                String query,
                                                                Map<String, Object> metadata) {
        return InformationGenerationResponseSupport.generationOnly(
            intent,
            pipelineContext,
            query,
            metadata,
            ragResponseGenerationSupport
        );
    }

    private OrchestrationResult handleInformationFromReadActionEvidence(Intent intent,
                                                                        PipelineContext pipelineContext,
                                                                        String generationQuery,
                                                                        Map<String, Object> metadata,
                                                                        ReadActionResolutionService.ResolutionOutcome resolutionOutcome) {
        return InformationGenerationResponseSupport.fromReadActionEvidence(
            intent,
            pipelineContext,
            generationQuery,
            metadata,
            resolutionOutcome,
            ragResponseGenerationSupport
        );
    }

    private boolean isDeterministicInformationMode(PipelineContext pipelineContext) {
        return advancedRagSupport.isDeterministicInformationMode(pipelineContext);
    }

    private OrchestrationResult handleOutOfScope(Intent intent) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(intent.getActionParams())) {
            data.put(DATA_KEY_DETAILS, intent.getActionParams());
        }
        String userMessage = outOfScopeUserMessage(intent);
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.OUT_OF_SCOPE)
            .success(true)
            .message(userMessage)
            .data(Collections.unmodifiableMap(data))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    private String outOfScopeUserMessage(Intent intent) {
        if (intent != null && intent.getActionParams() != null && !intent.getActionParams().isEmpty()) {
            for (String key : List.of("userMessage", "message", "answer", "response")) {
                Object value = intent.getActionParams().get(key);
                if (value != null && StringUtils.hasText(value.toString())) {
                    return value.toString().trim();
                }
            }
        }
        return MSG_OUT_OF_SCOPE;
    }

    private OrchestrationResult handleCompoundIntents(MultiIntentResponse response, OrchestrationContext context, PipelineContext pipelineContext) {
        response = actionBatchSupport.coalesceBatchActionIntents(response);
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return OrchestrationResult.error("No intents to process.");
        }
        if (response.getIntents().size() == 1) {
            return handleSingleIntent(response.getIntents().getFirst(), context, pipelineContext);
        }
        List<OrchestrationResult> childResults = new ArrayList<>();
        List<NextStepRecommendation> nextSteps = new ArrayList<>();

        for (Intent intent : response.getIntents()) {
            OrchestrationResult child = handleSingleIntent(intent, context, pipelineContext);
            if (child == null) {
                log.error("handleSingleIntent returned null for intent type: {}",
                    intent != null ? intent.getType() : "NULL_INTENT");
                continue;
            }
            childResults.add(child);
            nextSteps.addAll(child.getNextSteps());
        }

        // Compound requests often include a primary action plus optional follow-up intents (ex: confirmation/help text).
        // Treat the compound as successful if at least one child succeeded and we did not hit a hard ERROR.
        // This prevents "action succeeded but follow-up failed" scenarios from being recorded as a total failure.
        boolean anySuccess = childResults.stream().anyMatch(OrchestrationResult::isSuccess);
        boolean anyError = childResults.stream().anyMatch(result -> result.getType() == OrchestrationResultType.ERROR);
        boolean allSuccess = !childResults.isEmpty() && childResults.stream().allMatch(OrchestrationResult::isSuccess);
        boolean success = anySuccess && !anyError;
        Map<String, Object> data = Map.of(DATA_KEY_RESULTS, childResults);

        return OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(success)
            .message(allSuccess ? MSG_ALL_PROCESSED : MSG_SOME_FAILED)
            .children(Collections.unmodifiableList(childResults))
            .nextSteps(Collections.unmodifiableList(nextSteps))
            .data(data)
            .build();
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private List<NextStepRecommendation> extractNextSteps(Intent intent) {
        if (intent.getNextStepRecommended() == null) {
            return List.of();
        }
        return List.of(intent.getNextStepRecommended());
    }

    private AIActionMetaData getMetadataForAction(String actionName) {
        try {
            Optional<AIActionMetaData> optional = actionHandlerRegistry.findMetadata(actionName);
            return optional != null ? optional.orElse(null) : null;
        } catch (Exception ex) {
            log.debug("Unable to resolve metadata for action {}: {}", actionName, ex.getMessage());
            return null;
        }
    }

}
