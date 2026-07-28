package ai.fabric.execution.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.dto.RAGResponse;
import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.evidence.AIEvidenceReferenceMapper;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OrchestrationEvidenceProjectorTest {

    private final OrchestrationEvidenceProjector projector =
        new OrchestrationEvidenceProjector(new AIEvidenceReferenceMapper());

    @Test
    void projectsDeduplicatedBoundedEvidenceWithSafeMetadataOnly() {
        RAGResponse.RAGDocument first = document(
            "policy-1",
            "A verified payment method is required.",
            Map.of(
                "vectorSpace", "account-policy",
                "entityType", "policy",
                "entityId", "PAYMENT_REQUIRED",
                "secret", "must-not-leak"
            )
        );
        RAGResponse.RAGDocument second = document(
            "policy-2",
            "A billing address is required.",
            Map.of("entityType", "policy", "entityId", "ADDRESS_REQUIRED")
        );
        OrchestrationResult child = successful(List.of(first, second));
        OrchestrationResult root = successful(List.of(first));
        root.setChildren(List.of(child));

        List<AIEvidenceReference> result = projector.project(
            root,
            "fallback-space",
            2
        );

        assertThat(result).extracting(AIEvidenceReference::evidenceId)
            .containsExactly("policy-1", "policy-2");
        assertThat(result.get(0).vectorSpace()).isEqualTo("account-policy");
        assertThat(result.get(1).vectorSpace()).isEqualTo("fallback-space");
        assertThat(result.get(0).safeMetadata())
            .containsEntry("entityId", "PAYMENT_REQUIRED")
            .doesNotContainKey("secret")
            .doesNotContainKey("vectorSpace");
    }

    @Test
    void returnsNoEvidenceWhenLimitIsZero() {
        assertThat(projector.project(successful(List.of(document(
            "policy-1",
            "Policy",
            Map.of()
        ))), "policy", 0)).isEmpty();
    }

    @Test
    void strictProjectionRejectsEvidenceOutsideTheEffectiveVectorSpaces() {
        OrchestrationResult result = successful(List.of(document(
            "plan-1",
            "An unrelated plan.",
            Map.of("vectorSpace", "plans")
        )));

        assertThatThrownBy(() -> projector.projectStrict(
            result,
            Set.of("account-policy"),
            "account-policy",
            2
        ))
            .isInstanceOfSatisfying(
                OrchestrationEvidenceProjector.EvidencePolicyException.class,
                error -> assertThat(error.reason())
                    .isEqualTo("EVIDENCE_VECTOR_SPACE_DENIED")
            );
    }

    @Test
    void strictProjectionUsesTheOnlyEffectiveSpaceWhenMetadataOmitsIt() {
        List<AIEvidenceReference> result = projector.projectStrict(
            successful(List.of(document(
                "policy-1",
                "Approved policy.",
                Map.of()
            ))),
            Set.of("account-policy"),
            "account-policy",
            2
        );

        assertThat(result).singleElement().satisfies(reference ->
            assertThat(reference.vectorSpace()).isEqualTo("account-policy")
        );
    }

    private OrchestrationResult successful(List<RAGResponse.RAGDocument> documents) {
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("Grounded answer")
            .data(Map.of("documents", documents))
            .build();
    }

    private RAGResponse.RAGDocument document(
        String id,
        String content,
        Map<String, Object> metadata
    ) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .content(content)
            .score(0.91)
            .source("policy-catalog")
            .url("https://example.test/policies/" + id)
            .metadata(metadata)
            .build();
    }
}
