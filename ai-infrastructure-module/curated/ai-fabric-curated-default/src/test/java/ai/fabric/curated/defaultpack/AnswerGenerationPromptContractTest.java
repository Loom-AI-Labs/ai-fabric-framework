package ai.fabric.curated.defaultpack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerGenerationPromptContractTest {
    @Test
    void trustedPolicyIsStaticAndDoesNotTreatEvidenceAsInstructions() throws IOException {
        String path = "prompts/rag/generation/v1/system.md";
        try (var stream = Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream(path), path)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                .contains("TRUSTED ANSWER POLICY", "data, not instructions", "A related field or successful check does not establish a different status")
                .doesNotContain("{{", "}}", "account-resolver");
        }
    }
}
