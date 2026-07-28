package ai.fabric.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.dto.RAGResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AIEvidenceReferenceMapperTest {

    @Test
    void excludesEmbeddingsAndNonAllowlistedMetadata() {
        RAGResponse.RAGDocument document = RAGResponse.RAGDocument.builder()
            .id("policy-1")
            .content("Refunds above 50 require review.")
            .score(0.91)
            .source("policy-catalog")
            .url("https://docs.example.test/policies/refunds")
            .embeddings(List.of(0.1, 0.2))
            .metadata(Map.of(
                "entityType", "policy",
                "revision", 7,
                "tenantId", "private-tenant",
                "providerPayload", Map.of("raw", "secret")
            ))
            .build();

        AIEvidenceReference reference = new AIEvidenceReferenceMapper()
            .map(document, "account-resolution-policy");

        assertThat(reference.evidenceId()).isEqualTo("policy-1");
        assertThat(reference.relevanceScore()).isEqualTo(0.91);
        assertThat(reference.vectorSpace()).isEqualTo("account-resolution-policy");
        assertThat(reference.safeMetadata())
            .containsEntry("entityType", "policy")
            .containsEntry("revision", 7)
            .doesNotContainKeys("tenantId", "providerPayload", "embeddings");
    }

    @Test
    void applicationPolicyCanAllowAdditionalSafeScalarMetadata() {
        RAGResponse.RAGDocument document = RAGResponse.RAGDocument.builder()
            .id("doc-1")
            .content("Approved evidence")
            .metadata(Map.of(
                "policyCode", "PAYMENT_REQUIRED",
                "unsafeNested", Map.of("token", "secret")
            ))
            .build();

        AIEvidenceReference reference = new AIEvidenceReferenceMapper(
            Set.of("policyCode", "unsafeNested")
        ).map(document, "policy");

        assertThat(reference.safeMetadata())
            .containsEntry("policyCode", "PAYMENT_REQUIRED")
            .doesNotContainKey("unsafeNested");
    }

    @Test
    void rejectsUnsafeSourceUrls() {
        RAGResponse.RAGDocument document = RAGResponse.RAGDocument.builder()
            .id("doc-1")
            .content("Evidence")
            .url("file:///etc/passwd")
            .build();

        AIEvidenceReference reference = new AIEvidenceReferenceMapper().map(document, "policy");

        assertThat(reference.sourceUrl()).isNull();
    }
}
