package ai.fabric.intent;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.extraction.CompoundIntentExtractionStrategy;
import ai.fabric.intent.extraction.CompletionIntentExtractionStrategy;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.extraction.RepairIntentExtractionStrategy;
import ai.fabric.intent.extraction.MultiStepIntentExtractionStrategy;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.information.ReadActionEligibilitySupport;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.config.PromptBundleProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class IntentExtractionReadPathTest {
    private static final String JSON = """
        {"intents":[{"type":"INFORMATION","intent":"public_guide",
         "requiresRetrieval":true,"requiresGeneration":true,"requiresTargetResolution":false,
         "needsAdvancedRAG":false,"vectorSpace":null}]}
        """;

    @ParameterizedTest
    @ValueSource(strings = {"qualified", "disabled", "private", "unavailable", "not-allowed",
        "ineligible", "write", "actions-disabled", "effective-denied"})
    void defersOnlyForQualifiedReadPathWithoutAuthorizingExecution(String scenario) {
        var registry = mock(AIActionRegistry.class);
        var metadata = AIActionMetaData.builder().name("inspect_guide")
            .accessMode(scenario.equals("write") ? ActionAccessMode.WRITE_ONLY : ActionAccessMode.READ)
            .anonymousAllowed(!scenario.equals("private"))
            .groundingEligible(true).readActionResolutionEligible(!scenario.equals("ineligible")).build();
        when(registry.getAllMetadata()).thenReturn(scenario.equals("disabled") ? List.of() : List.of(metadata));
        when(registry.findHandler(metadata.getName())).thenReturn(scenario.equals("unavailable")
            ? Optional.empty() : Optional.of(mock(AIActionHandler.class)));
        var context = context(registry, !scenario.equals("actions-disabled"), !scenario.equals("not-allowed"));
        if (scenario.equals("actions-disabled")) {
            context = context.toBuilder().effectiveCapabilityProfile(null).build();
        } else if (scenario.equals("effective-denied")) {
            var profile = context.getEffectiveCapabilityProfile();
            context = context.toBuilder().effectiveCapabilityProfile(new EffectiveCapabilityProfile(
                profile.profile(), profile.mode(), false, Set.of(), Set.of(), Set.of(), Set.of(),
                profile.ragBudgets(), profile.readActionResolutionPolicy(), "denied-fixture")).build();
        }
        var intent = Intent.builder().type(IntentType.INFORMATION).intent("public_guide")
            .requiresRetrieval(true).requiresGeneration(true).requiresTargetResolution(false)
            .needsAdvancedRAG(false).build();
        var result = new IntentExtractionValidator(registry).validate(
            MultiIntentResponse.builder().intents(List.of(intent)).build(), "Show the guide", context);
        boolean qualified = scenario.equals("qualified") || scenario.equals("private");
        assertThat(ReadActionEligibilitySupport.hasQualifiedReadPath(registry, context)).isEqualTo(qualified);
        assertThat(result.valid()).isEqualTo(qualified);
        assertThat(intent.getRequiresRetrieval()).isTrue();
        assertThat(intent.getVectorSpace()).isNull();
        if (!qualified) {
            assertThat(result.issues()).extracting(IntentExtractionValidator.ValidationIssue::code)
                .contains(IntentExtractionValidator.IssueCode.INFORMATION_MISSING_VECTOR_SPACE);
            if (!scenario.equals("actions-disabled")) {
                var ai = mock(AICoreService.class);
                var service = new ReadActionResolutionService(ai, registry,
                    new IntentExtractionJsonSupport(new ObjectMapper()), mock(PromptTemplateResolver.class),
                    new PromptRenderer(), mock(AIEntityConfigurationLoader.class));
                var outcome = service.resolve(intent, context, PipelineContext.from("Show the guide", context)
                    .toBuilder().orchestrationPolicy(context.getOrchestrationPolicy()).build());
                assertThat(outcome.attempted()).isFalse();
                assertThat(outcome.executedActions()).isEmpty();
                verifyNoInteractions(ai);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"compound", "completion", "repair", "legacy", "multi-step", "progressive"})
    void actualExtractionStrategiesRetainMissingVectorForQualifiedRead(String strategy) {
        var registry = mock(AIActionRegistry.class);
        var metadata = AIActionMetaData.builder().name("inspect_guide").accessMode(ActionAccessMode.READ)
            .anonymousAllowed(true).groundingEligible(true).readActionResolutionEligible(true).build();
        when(registry.getAllMetadata()).thenReturn(List.of(metadata));
        when(registry.findHandler(metadata.getName())).thenReturn(Optional.of(mock(AIActionHandler.class)));
        var context = context(registry, true, true);
        var ai = mock(AICoreService.class);
        when(ai.generateContent(any(), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder().content(JSON).build());
        var prompts = mock(EnrichedPromptBuilder.class);
        when(prompts.buildSystemPrompt(any(OrchestrationContext.class))).thenReturn("system");
        when(prompts.buildUserPrompt(any())).thenReturn("Show the guide");
        var mapper = new ObjectMapper();
        var json = new IntentExtractionJsonSupport(mapper);
        var validator = new IntentExtractionValidator(registry);
        var templates = new PromptTemplateResolver(new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties());
        var input = new IntentExtractionInput("Show the guide", "Show the guide", List.of());
        var compound = new CompoundIntentExtractionStrategy(ai, prompts, json, validator);
        var previous = compound.attemptExtract(input, context);
        assertThat(previous.isSuccess()).isTrue();
        MultiIntentResponse result = switch (strategy) {
            case "compound" -> previous.getResponse();
            case "completion" -> {
                var attempt = new CompletionIntentExtractionStrategy(ai, prompts, json, registry, validator,
                    templates, new PromptRenderer()).attemptComplete(input, context, previous);
                assertThat(attempt.isSuccess()).isTrue();
                yield attempt.getResponse();
            }
            case "repair" -> {
                var attempt = new RepairIntentExtractionStrategy(ai, json, validator, templates,
                    new PromptRenderer()).attemptRepair(input, context, previous);
                assertThat(attempt.isSuccess()).isTrue();
                yield attempt.getResponse();
            }
            case "multi-step" -> {
                var attempt = new MultiStepIntentExtractionStrategy(ai, registry, json, validator,
                    new PromptRenderer(), templates).attemptExtract(input, context);
                assertThat(attempt.isSuccess()).isTrue();
                yield attempt.getResponse();
            }
            case "progressive" -> new ProgressiveIntentExtractionEngine(new ProgressiveIntentExtractionProperties(),
                compound, mock(RepairIntentExtractionStrategy.class), mock(CompletionIntentExtractionStrategy.class),
                mock(MultiStepIntentExtractionStrategy.class), new IntentExtractionPostProcessor(registry), validator)
                .extract(input, context).response();
            default -> new IntentQueryExtractor(ai, prompts, registry, mapper, templates,
                new PromptRenderer()).extract(input, context);
        };
        assertThat(result.getIntents().getFirst().getVectorSpace()).isNull();
        assertThat(result.getIntents().getFirst().getRequiresRetrieval()).isTrue();
    }

    private OrchestrationContext context(AIActionRegistry registry, boolean actionsEnabled, boolean readAllowed) {
        var properties = new OrchestrationProperties();
        properties.setDefaultMode("executor");
        var mode = new OrchestrationProperties.ModeOverrides();
        mode.setActionsEnabled(actionsEnabled);
        mode.setRetrievalEnabled(false);
        var read = new OrchestrationProperties.ReadActionResolutionModeOverrides();
        read.setEnabled(true);
        read.setPlanningMode(OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE);
        read.setRequireAllowlist(true);
        read.setAllowedReadActions(readAllowed ? List.of("inspect_guide") : List.of());
        mode.setReadActionResolution(read);
        properties.getModes().put("executor", mode);
        var step = new OrchestrationPolicyResolutionStep(properties);
        ReflectionTestUtils.setField(step, "actionRegistry", registry);
        return step.process(PipelineContext.from("Show the guide", OrchestrationContext.anonymous()))
            .getOrchestrationContext();
    }
}
