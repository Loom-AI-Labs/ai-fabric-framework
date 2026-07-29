package ai.fabric.execution.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SpecialistHandoffPolicyTest {

    @Test
    void snapshotsExactTargetsAndRemainsDisabledByDefault() {
        LinkedHashSet<SpecialistId> mutable = new LinkedHashSet<>();
        SpecialistId target = SpecialistId.of("account-checker", "1");
        mutable.add(target);

        SpecialistHandoffPolicy policy =
            SpecialistHandoffPolicy.oneLevel(mutable);
        mutable.clear();

        assertThat(policy.allowedTargets()).containsExactly(target);
        assertThat(policy.allows(target)).isTrue();
        assertThat(SpecialistHandoffPolicy.disabled().enabled()).isFalse();
        assertThatThrownBy(() -> policy.allowedTargets().clear())
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullAndExcessiveTargets() {
        LinkedHashSet<SpecialistId> withNull = new LinkedHashSet<>();
        withNull.add(null);
        assertThatThrownBy(() ->
            SpecialistHandoffPolicy.oneLevel(withNull)
        )
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("handoff target");

        Set<SpecialistId> excessive = IntStream
            .rangeClosed(1, SpecialistHandoffPolicy.MAX_TARGETS + 1)
            .mapToObj(index ->
                SpecialistId.of("target-" + index, "1")
            )
            .collect(java.util.stream.Collectors.toCollection(
                LinkedHashSet::new
            ));
        assertThatThrownBy(() ->
            SpecialistHandoffPolicy.oneLevel(excessive)
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at most 8 handoff targets");
    }

    @Test
    void changesTheCanonicalSpecialistContentHash() {
        SpecialistId target = SpecialistId.of("account-checker", "1");
        RegisteredSpecialist disabled = RegisteredSpecialist.javaDefinition(
            definition(SpecialistHandoffPolicy.disabled())
        );
        RegisteredSpecialist enabled = RegisteredSpecialist.javaDefinition(
            definition(SpecialistHandoffPolicy.oneLevel(Set.of(target)))
        );

        assertThat(enabled.contentHash())
            .isNotEqualTo(disabled.contentHash());
    }

    private SpecialistDefinition<String, String> definition(
        SpecialistHandoffPolicy handoffPolicy
    ) {
        return new SpecialistDefinition<>(
            new SpecialistIdentity(
                SpecialistId.of("account-intake", "1"),
                "Account Intake",
                "Routes one account request"
            ),
            new SpecialistInstructions("Route the request", null),
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
            SpecialistDelegationPolicy.disabled(),
            handoffPolicy,
            new SpecialistInputAdapter<>() {
                @Override
                public Class<String> inputType() {
                    return String.class;
                }

                @Override
                public void validate(String input) {}

                @Override
                public String renderModelInput(String input) {
                    return input;
                }

                @Override
                public OrchestrationContext orchestrationContext(
                    String input
                ) {
                    return OrchestrationContext.builder().build();
                }
            },
            new SpecialistOutputAdapter<>() {
                @Override
                public Class<String> outputType() {
                    return String.class;
                }

                @Override
                public String project(
                    OrchestrationResult result,
                    List<ai.fabric.evidence.AIEvidenceReference> evidence
                ) {
                    return result.getMessage();
                }

                @Override
                public void validate(String output) {
                    if (output == null || output.isBlank()) {
                        throw new IllegalArgumentException(
                            "output is required"
                        );
                    }
                }
            }
        );
    }
}
