package com.ai.fabric.realapps.retrievallab;

import com.ai.fabric.realapps.retrievallab.service.RetrievalBoundaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("smoke")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
        "server.port=${test.boundary.port}",
        "ai.retrieval.connector.base-url="
            + "http://127.0.0.1:${test.boundary.port}/fixture",
        "test.boundary.port=${TEST_BOUNDARY_PORT:18105}",
        "debug=false"
    }
)
class RetrievalBoundaryIntegrationTest {

    @Autowired
    private RetrievalBoundaryService service;

    @Test
    void validEvidenceReachesGenerationAfterProjection() {
        RetrievalBoundaryService.BoundaryOutcome outcome = service.run(
            new RetrievalBoundaryService.BoundaryRequest(
                "VALID",
                "Can I return an opened laptop?"
            )
        );

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.retrievalAccepted()).isTrue();
        assertThat(outcome.generationInvoked()).isTrue();
        assertThat(outcome.answer()).contains("[smoke profile]");
        assertThat(outcome.documents()).hasSize(1);
        assertThat(outcome.documents().getFirst().get("vectorSpace"))
            .isEqualTo("policy");
        assertThat(outcome.documents().getFirst().get("metadata"))
            .isEqualTo(java.util.Map.of("locale", "en_GB"));
        assertThat(outcome.documents().getFirst().toString())
            .doesNotContain("internalTenant", "must-not-cross");
    }

    @Test
    void rejectedEvidenceNeverReachesGeneration() {
        Map<String, String> expectedCodes =
            Map.of(
                "TENANT_DENIAL",
                "ACCESS_DENIED",
                "GENERATED_ANSWER_INJECTION",
                "INVALID_RESPONSE",
                "CROSS_VECTOR_SPACE",
                "VECTOR_SPACE_MISMATCH",
                "UNSAFE_URL",
                "URL_POLICY_VIOLATION",
                "RESERVED_METADATA",
                "METADATA_POLICY_VIOLATION"
            );
        for (Map.Entry<String, String> expected
            : expectedCodes.entrySet()) {
            String scenario = expected.getKey();
            RetrievalBoundaryService.BoundaryOutcome outcome = service.run(
                new RetrievalBoundaryService.BoundaryRequest(
                    scenario,
                    "Can I return an opened laptop?"
                )
            );

            assertThat(outcome.success())
                .as(scenario)
                .isFalse();
            assertThat(outcome.retrievalAccepted())
                .as(scenario)
                .isFalse();
            assertThat(outcome.generationInvoked())
                .as(scenario)
                .isFalse();
            assertThat(outcome.documents()).as(scenario).isEmpty();
            assertThat(outcome.answer()).as(scenario).isNull();
            assertThat(outcome.errorCode())
                .as(scenario)
                .isEqualTo(expected.getValue());
        }
    }
}
