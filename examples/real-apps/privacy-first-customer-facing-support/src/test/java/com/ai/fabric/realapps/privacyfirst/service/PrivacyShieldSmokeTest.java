package com.ai.fabric.realapps.privacyfirst.service;

import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PrivacyShieldSmokeTest {

    @Autowired
    private PrivacyDemoService privacyDemoService;

    @Test
    void searchOutputRedactsSensitiveInput() {
        // Arrange: Create a fake session ID
        String sessionId = "smoke-test-session";
        privacyDemoService.createSession(sessionId); // Sets up internal test data

        // Set up the sensitive values defined in the acceptance criteria
        String sensitiveEmail = "testuser99@example.com";
        String sensitivePhone = "+1 (555) 123-4567";
        String sensitivePayment = "4532-9876-1234-5678";

        String rawQuery = String.format("Find billing info for %s, contact %s, using card %s",
                sensitiveEmail, sensitivePhone, sensitivePayment);

        // Act: Pass the raw query into the Privacy Shield search
        PrivacyDemoService.SearchResult result = privacyDemoService.search(sessionId, rawQuery, 5);

        // Assert: The original sensitive values MUST NOT be present in the processed output
        String safeQuery = result.processedQuery();

        assertThat(safeQuery).doesNotContain(sensitiveEmail);
        assertThat(safeQuery).doesNotContain(sensitivePhone);
        assertThat(safeQuery).doesNotContain(sensitivePayment);

        // Verify that PII was indeed detected and flagged by the Privacy Shield
        assertThat(result.queryPiiDetected()).isTrue();
    }
}
