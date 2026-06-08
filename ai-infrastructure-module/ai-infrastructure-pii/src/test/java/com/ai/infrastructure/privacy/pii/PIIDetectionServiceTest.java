package com.ai.infrastructure.privacy.pii;

import com.ai.infrastructure.config.PIIDetectionProperties;
import com.ai.infrastructure.dto.PIIDetectionResult;
import com.ai.infrastructure.dto.PIIMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}

