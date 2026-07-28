package ai.fabric.intent.orchestration.capability;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EffectiveCapabilityPolicySupportTest {

    @Test
    void narrowsModePolicyToThePreResolvedExecutionProfile() {
        OrchestrationPolicy modePolicy = new OrchestrationPolicy(
            null,
            "resolver",
            "resolver",
            null,
            new OrchestrationPolicy.OrchestrationCapabilities(
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                true,
                true,
                true,
                true
            ),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE,
                List.of("inspect_account", "list_plans"),
                true,
                2,
                2,
                3,
                1,
                3_000,
                1_800,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode
                    .PARALLEL_ACTIONS_AND_RAG,
                true
            ),
            new OrchestrationPolicy.RagBudgets(
                true,
                2,
                3,
                4,
                4,
                3_000,
                List.of("account-policy", "plans")
            ),
            null
        );
        EffectiveCapabilityProfile effective = new EffectiveCapabilityProfile(
            "DEFAULT",
            "resolver",
            true,
            Set.of("account-policy"),
            Set.of("inspect_account"),
            Set.of("inspect_account"),
            Set.of(),
            new OrchestrationPolicy.RagBudgets(
                true,
                1,
                3,
                4,
                4,
                3_000,
                List.of("account-policy")
            ),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE,
                List.of("inspect_account"),
                true,
                2,
                1,
                2,
                1,
                3_000,
                1_800,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode
                    .PARALLEL_ACTIONS_AND_RAG,
                true
            ),
            "profile-hash"
        );

        OrchestrationPolicy constrained =
            EffectiveCapabilityPolicySupport.constrain(modePolicy, effective);

        assertThat(constrained.ragBudgets().retrievalVectorSpacesAllowlist())
            .containsExactly("account-policy");
        assertThat(constrained.ragBudgets().maxSpaces()).isEqualTo(1);
        assertThat(constrained.readActionResolutionPolicy().allowedReadActions())
            .containsExactly("inspect_account");
        assertThat(constrained.capabilities().actionsEnabled()).isTrue();
        assertThat(constrained.capabilities().retrievalEnabled()).isTrue();
        assertThat(constrained.capabilities().suggestionsEnabled()).isFalse();
        assertThat(constrained.capabilities().knowledgeBaseOverviewEnabled()).isFalse();
    }
}
