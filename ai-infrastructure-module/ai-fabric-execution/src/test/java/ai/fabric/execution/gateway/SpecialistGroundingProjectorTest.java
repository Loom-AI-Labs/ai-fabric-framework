package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpecialistGroundingProjectorTest {

    private final SpecialistGroundingProjector projector =
        new SpecialistGroundingProjector();

    @Test
    void projectsOnlyApprovedSanitizedTextAndCanonicalEvidence() {
        OrchestrationResult child = OrchestrationResult.builder()
            .type(OrchestrationResultType.ACTION_EXECUTED)
            .success(true)
            .message("Current account has no verified payment method.")
            .data(Map.of(
                "answer",
                "Payment is missing.",
                "accountProfile",
                Map.of("secret", "must-not-reach-finalization")
            ))
            .build();
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.COMPOUND_HANDLED)
            .success(true)
            .message("All requests processed.")
            .children(List.of(child))
            .build();
        AIEvidenceReference evidence = new AIEvidenceReference(
            "policy-payment",
            "A verified payment method is required.",
            0.97,
            "policy-catalog",
            null,
            "account-policy",
            Map.of("internalNote", "must-not-reach-finalization")
        );

        SpecialistGroundingEnvelope envelope = projector.project(
            result,
            List.of(evidence),
            2_000
        );

        assertThat(envelope.results())
            .extracting(SpecialistGroundingEnvelope.ResultExcerpt::text)
            .containsExactly(
                "All requests processed.",
                "Current account has no verified payment method.",
                "Payment is missing."
            );
        assertThat(envelope.evidence()).singleElement().satisfies(reference -> {
            assertThat(reference.evidenceId()).isEqualTo("policy-payment");
            assertThat(reference.content())
                .isEqualTo("A verified payment method is required.");
            assertThat(reference.vectorSpace()).isEqualTo("account-policy");
        });
        assertThat(envelope.toString())
            .doesNotContain("secret")
            .doesNotContain("internalNote");
        assertThat(envelope.truncated()).isFalse();
    }

    @Test
    void enforcesGroundingCharacterBudget() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("0123456789".repeat(30))
            .build();

        SpecialistGroundingEnvelope envelope = projector.project(
            result,
            List.of(),
            25
        );

        assertThat(envelope.results()).singleElement().satisfies(excerpt ->
            assertThat(excerpt.text()).hasSize(25)
        );
        assertThat(envelope.truncated()).isTrue();
    }

    @Test
    void includesOnlyGroundingUsableServerProducedReadActionFacts() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Account profile analyzed.")
            .data(Map.of(
                "readActionResolution",
                Map.of(
                    "executedActions",
                    List.of(
                        Map.of(
                            "action",
                            "get_account_profile",
                            "groundingUsable",
                            true,
                            "evidenceSummary",
                            "{\"subscriptionActive\":true,\"paymentMethodVerified\":false}",
                            "params",
                            Map.of("userId", "must-not-reach-finalization")
                        ),
                        Map.of(
                            "action",
                            "untrusted_lookup",
                            "groundingUsable",
                            false,
                            "evidenceSummary",
                            "{\"secret\":\"must-not-reach-finalization\"}"
                        )
                    )
                )
            ))
            .build();

        SpecialistGroundingEnvelope envelope = projector.project(
            result,
            List.of(),
            2_000
        );

        assertThat(envelope.results())
            .extracting(
                SpecialistGroundingEnvelope.ResultExcerpt::resultType,
                SpecialistGroundingEnvelope.ResultExcerpt::text
            )
            .contains(
                org.assertj.core.groups.Tuple.tuple(
                    "READ_ACTION_FACTS.get_account_profile",
                    "{\"subscriptionActive\":true,\"paymentMethodVerified\":false}"
                )
            );
        assertThat(envelope.toString())
            .doesNotContain("must-not-reach-finalization")
            .doesNotContain("untrusted_lookup");
    }
}
