package ai.fabric.privacy.pii;

import ai.fabric.config.PIIDetectionProperties;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.dto.PIIMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PIIDetectionServiceTest {

    @Test
    void shouldRedactSensitiveDataWhenRedactModeEnabled() {
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setMode(PIIMode.REDACT);
        properties.setStoreEncryptedOriginal(true);

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        String query = "Contact me at john.doe@example.com or charge card 4532-9876-1234-5678.";

        PIIDetectionResult result = service.detectAndProcess(query);

        assertThat(result.isPiiDetected()).isTrue();
        assertThat(result.getDetections()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result.getProcessedQuery())
            .doesNotContain("john.doe@example.com")
            .doesNotContain("4532-9876-1234-5678")
            .contains("***@***.***")
            .contains("****-****-****-****");
        assertThat(result.getEncryptedOriginalQuery()).isNotNull();
    }

    @Test
    void shouldDetectButNotRedactWhenInDetectOnlyMode() {
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setMode(PIIMode.DETECT_ONLY);

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        String query = "My phone number is (415) 555-8999.";

        PIIDetectionResult result = service.detectAndProcess(query);

        assertThat(result.isPiiDetected()).isTrue();
        assertThat(result.getProcessedQuery()).isEqualTo(query);
        assertThat(result.getDetections()).hasSize(1);
        assertThat(result.getDetections().getFirst().getMaskedValue()).isEqualTo("***-***-****");
    }

    @Test
    void shouldPassThroughWhenDetectionDisabled() {
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(false);
        properties.setMode(PIIMode.REDACT);

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        String query = "This text should not be processed.";

        PIIDetectionResult result = service.detectAndProcess(query);

        assertThat(result.isPiiDetected()).isFalse();
        assertThat(result.getProcessedQuery()).isEqualTo(query);
        assertThat(result.getDetections()).isEmpty();
        assertThat(result.getModeApplied()).isEqualTo(PIIMode.PASS_THROUGH);
    }

    @Test
    void analyzeShouldDetectMatchesWithoutMutatingPayload() {
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setMode(PIIMode.DETECT_ONLY);

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        String payload = "Reach me at secure@example.com for details.";

        PIIDetectionResult analysis = service.analyze(payload);

        assertThat(analysis.isPiiDetected()).isTrue();
        assertThat(analysis.getProcessedQuery()).isEqualTo(payload);
        assertThat(analysis.getDetections()).hasSize(1);
        assertThat(analysis.getDetections().getFirst().getType()).isEqualTo("EMAIL");
        assertThat(analysis.getModeApplied()).isEqualTo(PIIMode.DETECT_ONLY);
    }

    @Test
    void shouldTreatNullPatternMapAsNoConfiguredPatterns() {
        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setMode(PIIMode.REDACT);
        properties.setPatterns(null);

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        String query = "Contact me at john.doe@example.com.";
        PIIDetectionResult result = service.detectAndProcess(query);

        assertThat(result.isPiiDetected()).isFalse();
        assertThat(result.getProcessedQuery()).isEqualTo(query);
        assertThat(result.getDetections()).isEmpty();
        assertThat(result.getMetadata()).containsEntry("patternsEvaluated", 0);
    }

    @Test
    void shouldDefaultNullReplacementAndClampConfidence() {
        PIIDetectionProperties.PatternConfig tokenPattern = PIIDetectionProperties.PatternConfig.builder()
            .fieldName("api_token")
            .regex("sk-[A-Za-z0-9]+")
            .replacement(null)
            .confidence(2.5d)
            .build();

        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setEnabled(true);
        properties.setMode(PIIMode.REDACT);
        properties.setPatterns(Map.of("API_TOKEN", tokenPattern));

        PIIDetectionService service = new DefaultPIIDetectionService(properties);

        PIIDetectionResult result = service.detectAndProcess("token sk-secret123");

        assertThat(result.isPiiDetected()).isTrue();
        assertThat(result.getProcessedQuery()).isEqualTo("token ***");
        assertThat(result.getDetections()).hasSize(1);
        assertThat(result.getDetections().getFirst().getMaskedValue()).isEqualTo("***");
        assertThat(result.getDetections().getFirst().getConfidence()).isEqualTo(1.0d);
    }

    @Test
    void shouldRejectBlankRegexForEnabledPattern() {
        PIIDetectionProperties.PatternConfig blankPattern = PIIDetectionProperties.PatternConfig.builder()
            .regex(" ")
            .build();

        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setPatterns(Map.of("BAD", blankPattern));

        assertThatThrownBy(() -> new DefaultPIIDetectionService(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BAD")
            .hasMessageContaining("non-blank regex");
    }

    @Test
    void shouldRejectInvalidRegexWithPatternName() {
        PIIDetectionProperties.PatternConfig invalidPattern = PIIDetectionProperties.PatternConfig.builder()
            .regex("[")
            .build();

        PIIDetectionProperties properties = new PIIDetectionProperties();
        properties.setPatterns(Map.of("BROKEN", invalidPattern));

        assertThatThrownBy(() -> new DefaultPIIDetectionService(properties))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BROKEN")
            .hasMessageContaining("Invalid PII detection regex");
    }
}
