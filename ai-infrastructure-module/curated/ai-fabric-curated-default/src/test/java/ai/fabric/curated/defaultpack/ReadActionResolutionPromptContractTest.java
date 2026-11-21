package ai.fabric.curated.defaultpack;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ReadActionResolutionPromptContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"v1", "v1-default-optimized"})
    void separatesEvidencePresenceFromCapabilityDescriptions(String version) throws IOException {
        assertThat(read(version, "user"))
            .contains("{{evidence_inputs_json}}", "{{knowledge_sources_json}}", "{{prior_evidence_json}}")
            .contains("server-owned counts, not business facts");
        assertThat(read("v1", "system"))
            .contains("Action descriptions, schemas and source catalogues describe available help; they are not returned facts")
            .contains("An extraction draft is not evidence")
            .contains("A public policy READ is an evidence source even when indexed retrieval is disabled")
            .contains("general conversation and supported history-only transformations do not need a needless tool call");
    }

    private String read(String version, String name) throws IOException {
        String path = "prompts/orchestration/read-action-resolution/" + version + "/" + name + ".md";
        try (var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path), path)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
