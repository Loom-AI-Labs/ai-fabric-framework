package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrchestrationPolicyResolutionStep")
class OrchestrationPolicyResolutionStepTest {

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
            @DisplayName("Should have correct step name")
            void shouldHaveCorrectStepName() {
            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(new OrchestrationProperties());
                assertThat(step.getStepName()).isEqualTo("OrchestrationPolicyResolution");
            }

        @Test
            @DisplayName("Should have correct order (after access control)")
            void shouldHaveCorrectOrder() {
            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(new OrchestrationProperties());
                assertThat(step.getOrder()).isEqualTo(22);
            }
    }

    @Nested
    @DisplayName("process()")
    class ProcessMethod {

        @Test
        @DisplayName("Should attach resolved policy to context and metadata (default mode; position ignored)")
        void shouldAttachResolvedPolicyToContextAndMetadataDefaultModePositionIgnored() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.DEFAULT);

            OrchestrationProperties.ModeOverrides navigator = new OrchestrationProperties.ModeOverrides();
            navigator.setInformationMode(OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE);
            orchestrationProperties.getModes().put("navigator", navigator);
            orchestrationProperties.getPositionRouting().put("landing", "navigator");
            orchestrationProperties.setDefaultMode("navigator");

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orch = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .position("landing")
                .build();

            PipelineContext input = PipelineContext.from("hello", orch);
            PipelineContext output = step.process(input);

            OrchestrationPolicy policy = output.getOrchestrationPolicy();
            assertThat(policy).isNotNull();
            assertThat(policy.profile()).isEqualTo(OrchestrationProfile.DEFAULT);
            assertThat(policy.mode()).isEqualTo("navigator");
            assertThat(policy.position()).isEqualTo("landing");
            assertThat(policy.informationMode()).isEqualTo(OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE);

            assertThat(output.getOrchestrationContext()).isNotNull();
            assertThat(output.getOrchestrationContext().getOrchestrationPolicy()).isEqualTo(policy);

            Object meta = output.getMetadataView().get("orchestrationPolicy");
            assertThat(meta).isInstanceOf(Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) meta;
            assertThat(policyMeta).containsEntry("profile", "DEFAULT");
            assertThat(policyMeta).containsEntry("mode", "navigator");
            assertThat(policyMeta).containsEntry("position", "landing");
            assertThat(policyMeta).containsEntry("informationModeEffective", "DETERMINISTIC_RAG_GENERATE");
            assertThat(policyMeta).containsEntry("modeSource", "DEFAULT_MODE");
        }

        @Test
        @DisplayName("Should use requested mode when allowlisted and no position routing")
        void shouldUseRequestedModeWhenAllowlistedAndNoPositionRouting() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.DEFAULT);

            OrchestrationProperties.ModeOverrides navigator = new OrchestrationProperties.ModeOverrides();
            orchestrationProperties.getModes().put("navigator", navigator);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orch = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .mode("navigator")
                .build();

            PipelineContext output = step.process(PipelineContext.from("hello", orch));
            assertThat(output.getOrchestrationPolicy().mode()).isEqualTo("navigator");

            Object meta = output.getMetadataView().get("orchestrationPolicy");
            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) meta;
            assertThat(policyMeta).containsEntry("modeSource", "REQUEST_MODE");
        }

        @Test
        @DisplayName("Should fall back to profile defaults when no mode is resolved")
        void shouldFallBackToProfileDefaultsWhenNoModeIsResolved() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            PipelineContext output = step.process(PipelineContext.from("hello", OrchestrationContext.forUser("user-123")));
            OrchestrationPolicy policy = output.getOrchestrationPolicy();

            assertThat(policy.profile()).isEqualTo(OrchestrationProfile.PRODUCTION_NAVIGATOR);
            assertThat(policy.mode()).isNull();
            assertThat(policy.informationMode()).isEqualTo(OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE);
            assertThat(policy.capabilities().forceGroundingEligibleReadActionPostGeneration()).isFalse();
        }

        @Test
        @DisplayName("Should resolve forced grounding-eligible read-action post-generation mode capability")
        void shouldResolveForcedGroundingEligibleReadActionPostGenerationModeCapability() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_CHAT);

            OrchestrationProperties.ModeOverrides thinker = new OrchestrationProperties.ModeOverrides();
            thinker.setForceGroundingEligibleReadActionPostGeneration(true);
            orchestrationProperties.getModes().put("thinker", thinker);
            orchestrationProperties.setDefaultMode("thinker");

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            PipelineContext output = step.process(PipelineContext.from("hello", OrchestrationContext.forUser("user-123")));

            assertThat(output.getOrchestrationPolicy().capabilities().forceGroundingEligibleReadActionPostGeneration()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("forceGroundingEligibleReadActionPostGeneration", true);
        }

        @Test
        @DisplayName("Should apply deployment rag similarity threshold override from metadata")
        void shouldApplyDeploymentRagSimilarityThresholdOverrideFromMetadata() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .metadata(Map.of("ragSimilarityThreshold", 0.1d))
                .build();

            PipelineContext output = step.process(PipelineContext.from("hello", orchestrationContext));

            assertThat(output.getOrchestrationPolicy().ragBudgets().similarityThreshold()).isEqualTo(0.1d);
            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("ragSimilarityThreshold", 0.1d);
            assertThat(policyMeta).containsEntry("ragSimilarityThresholdSource", "DEPLOYMENT_CONFIG");
        }

        @Test
        @DisplayName("Should apply deployment smart suggestions override from metadata")
        void shouldApplyDeploymentSmartSuggestionsOverrideFromMetadata() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);

            OrchestrationProperties.ModeOverrides navigator = new OrchestrationProperties.ModeOverrides();
            navigator.setSuggestionsEnabled(true);
            orchestrationProperties.getModes().put("navigator", navigator);
            orchestrationProperties.setDefaultMode("navigator");

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .metadata(Map.of("smartSuggestionsEnabled", false))
                .build();

            PipelineContext output = step.process(PipelineContext.from("hello", orchestrationContext));

            assertThat(output.getOrchestrationPolicy().capabilities().suggestionsEnabled()).isFalse();
            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("suggestionsEnabled", false);
            assertThat(policyMeta).containsEntry("suggestionsEnabledSource", "DEPLOYMENT_CONFIG");
        }

        @Test
        @DisplayName("Should apply deployment RAG generation context budget overrides from metadata")
        void shouldApplyDeploymentRagGenerationContextBudgetOverridesFromMetadata() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .metadata(Map.of(
                    "ragMaxDocumentsUsedForContext", 6,
                    "ragMaxContextChars", 4_500
                ))
                .build();

            PipelineContext output = step.process(PipelineContext.from("hello", orchestrationContext));

            assertThat(output.getOrchestrationPolicy().ragBudgets().maxDocumentsUsedForContext()).isEqualTo(6);
            assertThat(output.getOrchestrationPolicy().ragBudgets().maxContextChars()).isEqualTo(4_500);
            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("ragMaxDocumentsUsedForContext", 6);
            assertThat(policyMeta).containsEntry("ragMaxDocumentsUsedForContextSource", "DEPLOYMENT_CONFIG");
            assertThat(policyMeta).containsEntry("ragMaxContextChars", 4_500);
            assertThat(policyMeta).containsEntry("ragMaxContextCharsSource", "DEPLOYMENT_CONFIG");
        }

        @Test
        @DisplayName("Should apply deployment response generation token budget overrides from metadata")
        void shouldApplyDeploymentResponseGenerationTokenBudgetOverridesFromMetadata() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .metadata(Map.of(
                    "responseGenerationMaxTokensConcise", 400,
                    "responseGenerationMaxTokensStandard", 900,
                    "responseGenerationMaxTokensDeep", 1_400
                ))
                .build();

            PipelineContext output = step.process(PipelineContext.from("hello", orchestrationContext));

            assertThat(output.getOrchestrationPolicy().responseGenerationBudgets().conciseMaxTokens()).isEqualTo(400);
            assertThat(output.getOrchestrationPolicy().responseGenerationBudgets().standardMaxTokens()).isEqualTo(900);
            assertThat(output.getOrchestrationPolicy().responseGenerationBudgets().deepMaxTokens()).isEqualTo(1_400);
            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("responseGenerationMaxTokensConcise", 400);
            assertThat(policyMeta).containsEntry("responseGenerationMaxTokensStandard", 900);
            assertThat(policyMeta).containsEntry("responseGenerationMaxTokensDeep", 1_400);
        }

        @Test
        @DisplayName("Should resolve read action resolution mode overrides into policy metadata")
        void shouldResolveReadActionResolutionModeOverridesIntoPolicyMetadata() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_CHAT);

            OrchestrationProperties.ModeOverrides resolver = new OrchestrationProperties.ModeOverrides();
            OrchestrationProperties.ReadActionResolutionModeOverrides readActionResolution =
                new OrchestrationProperties.ReadActionResolutionModeOverrides();
            readActionResolution.setEnabled(true);
            readActionResolution.setPlanningMode(OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS);
            readActionResolution.setRequireAllowlist(true);
            readActionResolution.setAllowedReadActions(java.util.List.of("get_policy", "check_availability"));
            readActionResolution.setMaxActionsPerIteration(2);
            readActionResolution.setMaxTotalActions(3);
            readActionResolution.setRagCooperationMode(
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT
            );
            resolver.setReadActionResolution(readActionResolution);
            orchestrationProperties.getModes().put("resolver_assistant", resolver);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);

            OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-123")
                .toBuilder()
                .mode("resolver_assistant")
                .build();

            PipelineContext output = step.process(PipelineContext.from("refund policy", orchestrationContext));

            assertThat(output.getOrchestrationPolicy().readActionResolutionPolicy().enabled()).isTrue();
            assertThat(output.getOrchestrationPolicy().readActionResolutionPolicy().planningMode())
                .isEqualTo(OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS);
            assertThat(output.getOrchestrationPolicy().readActionResolutionPolicy().allowedReadActions())
                .containsExactly("get_policy", "check_availability");

            @SuppressWarnings("unchecked")
            Map<String, Object> policyMeta = (Map<String, Object>) output.getMetadataView().get("orchestrationPolicy");
            assertThat(policyMeta).containsEntry("readActionResolutionEnabled", true);
            assertThat(policyMeta).containsEntry("readActionResolutionPlanningMode", "SINGLE_PASS");
            assertThat(policyMeta).containsEntry("readActionResolutionRequireAllowlist", true);
            assertThat(policyMeta).containsEntry("readActionResolutionAllowedReadActions", java.util.List.of("get_policy", "check_availability"));
        }

        @Test
        @DisplayName("Should apply explicit overrides over profile and mode defaults")
        void shouldApplyExplicitOverridesOverProfileAndModeDefaults() {
            OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
            orchestrationProperties.setProfile(OrchestrationProfile.PRODUCTION_NAVIGATOR);
            orchestrationProperties.setInformationMode(OrchestrationProperties.InformationMode.LLM_DRIVEN);

            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(orchestrationProperties);
            PipelineContext output = step.process(PipelineContext.from("hello", OrchestrationContext.forUser("user-123")));

            assertThat(output.getOrchestrationPolicy().informationMode()).isEqualTo(OrchestrationProperties.InformationMode.LLM_DRIVEN);
        }

        @Test
        @DisplayName("Should skip when pipeline already terminated")
        void shouldSkipWhenPipelineAlreadyTerminated() {
            OrchestrationPolicyResolutionStep step = new OrchestrationPolicyResolutionStep(new OrchestrationProperties());

            PipelineContext terminated = PipelineContext.from("hello", OrchestrationContext.forUser("user-123"))
                .terminate(ai.fabric.intent.orchestration.OrchestrationResult.error("nope"));

            assertThat(step.shouldSkip(terminated)).isTrue();
        }
    }
}
