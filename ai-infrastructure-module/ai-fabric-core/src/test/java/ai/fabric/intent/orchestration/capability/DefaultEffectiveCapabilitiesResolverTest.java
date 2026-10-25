package ai.fabric.intent.orchestration.capability;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DefaultEffectiveCapabilitiesResolverTest {

    @Test
    void intersectsRequestedRegisteredPolicyAndDeploymentCapabilities() {
        AIActionMetaData inspect = action("inspect_account", ActionAccessMode.READ);
        AIActionMetaData update = action("update_payment", ActionAccessMode.READ_WRITE);
        AIActionMetaData hidden = action("delete_account", ActionAccessMode.WRITE_ONLY);
        OrchestrationPolicy policy = new OrchestrationPolicy(
            null,
            "resolver",
            null,
            null,
            null,
            null,
            new OrchestrationPolicy.RagBudgets(
                true, 2, 5, 5, 5, 4_000, List.of("account", "policy")
            ),
            null
        );
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            true,
            Set.of("account", "policy", "other"),
            Set.of("inspect_account", "update_payment", "delete_account"),
            Set.of("inspect_account"),
            Set.of("update_payment", "delete_account")
        );

        EffectiveCapabilityProfile effective = new DefaultEffectiveCapabilitiesResolver().resolve(
            new CapabilityResolutionRequest(
                requested,
                policy,
                List.of(inspect, update, hidden),
                Set.of("account", "policy"),
                Set.of("inspect_account", "update_payment"),
                Set.of(),
                null
            )
        );

        assertThat(effective.effectiveVectorSpaces()).containsExactlyInAnyOrder("account", "policy");
        assertThat(effective.visibleActions())
            .containsExactlyInAnyOrder("inspect_account", "update_payment");
        assertThat(effective.executableReadActions()).containsExactly("inspect_account");
        assertThat(effective.proposableWriteActions()).containsExactly("update_payment");
        assertThat(effective.ragBudgets().retrievalVectorSpacesAllowlist())
            .containsExactly("account", "policy");
        assertThat(effective.ragBudgets().maxSpaces()).isEqualTo(2);
        assertThat(effective.readActionResolutionPolicy().allowedReadActions())
            .containsExactly("inspect_account");
        assertThat(effective.readActionResolutionPolicy().requireAllowlist()).isTrue();
        assertThat(effective.readActionResolutionPolicy().maxActionEvidenceItems()).isEqualTo(5);
        assertThat(effective.profileHash()).hasSize(64);
    }

    @Test
    void producesStableHashRegardlessOfInputSetOrder() {
        AIActionMetaData inspect = action("inspect_account", ActionAccessMode.READ);
        OrchestrationPolicy policy = new OrchestrationPolicy(null, "resolver", null, null, null, null, null, null);
        DefaultEffectiveCapabilitiesResolver resolver = new DefaultEffectiveCapabilitiesResolver();

        EffectiveCapabilityProfile first = resolver.resolve(new CapabilityResolutionRequest(
            new RequestedCapabilityProfile(
                true,
                Set.of("policy", "account"),
                Set.of("inspect_account"),
                Set.of("inspect_account"),
                Set.of()
            ),
            policy,
            List.of(inspect),
            Set.of("account", "policy"),
            Set.of(),
            Set.of(),
            null
        ));
        EffectiveCapabilityProfile second = resolver.resolve(new CapabilityResolutionRequest(
            new RequestedCapabilityProfile(
                true,
                new java.util.LinkedHashSet<>(List.of("account", "policy")),
                Set.of("inspect_account"),
                Set.of("inspect_account"),
                Set.of()
            ),
            policy,
            List.of(inspect),
            Set.of("policy", "account"),
            Set.of(),
            Set.of(),
            null
        ));

        assertThat(first.profileHash()).isEqualTo(second.profileHash());
    }

    @Test
    void profileHashChangesWhenEffectiveBudgetsChange() {
        AIActionMetaData inspect = action("inspect_account", ActionAccessMode.READ);
        RequestedCapabilityProfile requested = new RequestedCapabilityProfile(
            true,
            Set.of("account"),
            Set.of("inspect_account"),
            Set.of("inspect_account"),
            Set.of()
        );
        DefaultEffectiveCapabilitiesResolver resolver =
            new DefaultEffectiveCapabilitiesResolver();

        EffectiveCapabilityProfile first = resolver.resolve(
            new CapabilityResolutionRequest(
                requested,
                policyWithTopK(3),
                List.of(inspect),
                Set.of("account"),
                Set.of(),
                Set.of(),
                null
            )
        );
        EffectiveCapabilityProfile second = resolver.resolve(
            new CapabilityResolutionRequest(
                requested,
                policyWithTopK(7),
                List.of(inspect),
                Set.of("account"),
                Set.of(),
                Set.of(),
                null
            )
        );

        assertThat(first.profileHash()).isNotEqualTo(second.profileHash());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 17, 100})
    void preservesEvidenceItemBudgetWhenCopyingEffectiveReadPolicy(int budget) {
        DefaultEffectiveCapabilitiesResolver resolver = new DefaultEffectiveCapabilitiesResolver();
        OrchestrationPolicy policy = policyWithEvidenceItems(budget);

        EffectiveCapabilityProfile effective = resolver.resolveLegacy(
            policy,
            List.of(action("inspect_account", ActionAccessMode.READ))
        );
        EffectiveCapabilityProfile disabled = resolver.resolveLegacy(policy, List.of());

        assertThat(effective.readActionResolutionPolicy().enabled()).isTrue();
        assertThat(effective.readActionResolutionPolicy().maxActionEvidenceItems()).isEqualTo(budget);
        assertThat(disabled.readActionResolutionPolicy().enabled()).isFalse();
        assertThat(disabled.readActionResolutionPolicy().maxActionEvidenceItems()).isEqualTo(budget);
    }

    @Test
    void profileHashChangesWhenOnlyEvidenceItemBudgetChanges() {
        DefaultEffectiveCapabilitiesResolver resolver = new DefaultEffectiveCapabilitiesResolver();
        List<AIActionMetaData> actions = List.of(action("inspect_account", ActionAccessMode.READ));

        EffectiveCapabilityProfile first = resolver.resolveLegacy(policyWithEvidenceItems(5), actions);
        EffectiveCapabilityProfile second = resolver.resolveLegacy(policyWithEvidenceItems(17), actions);

        assertThat(first.profileHash()).isNotEqualTo(second.profileHash());
    }

    private OrchestrationPolicy policyWithEvidenceItems(int budget) {
        return new OrchestrationPolicy(
            null,
            "resolver",
            null,
            null,
            null,
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                List.of("inspect_account"),
                true,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                budget,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            ),
            null,
            null
        );
    }

    private OrchestrationPolicy policyWithTopK(int topK) {
        return new OrchestrationPolicy(
            null,
            "resolver",
            null,
            null,
            null,
            null,
            new OrchestrationPolicy.RagBudgets(
                true,
                2,
                topK,
                5,
                5,
                4_000,
                List.of("account", "other")
            ),
            null
        );
    }

    private AIActionMetaData action(String name, ActionAccessMode accessMode) {
        return AIActionMetaData.builder()
            .name(name)
            .accessMode(accessMode)
            .build();
    }
}
