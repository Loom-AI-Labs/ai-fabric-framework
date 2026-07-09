package com.ai.fabric.realapps.privacyfirst.service;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyDemoServiceTest {

    private final SupportMessageService supportMessageService = mock(SupportMessageService.class);
    private final PrivacyDemoService service = new PrivacyDemoService(supportMessageService);

    @Test
    void createSessionClearsAndSeedsOnlyTheSessionScopedCustomers() {
        when(supportMessageService.findByCustomerIdPrefix("privacy-test::")).thenReturn(List.of());

        PrivacyDemoService.DemoDashboard dashboard = service.createSession("privacy-test");

        assertThat(dashboard.sessionId()).isEqualTo("privacy-test");
        verify(supportMessageService).deleteByCustomerIdPrefix("privacy-test::");
        verify(supportMessageService).create(
            eq("privacy-test::billing"),
            eq("webchat"),
            eq("Billing update request"),
            eq("Hi, my email is sara.ahmed@example.com and my phone is +1 (555) 123-4567. Please update my subscription.")
        );
        verify(supportMessageService).create(
            eq("privacy-test::refund"),
            eq("email"),
            eq("Refund request"),
            eq("Order 81273 - my SSN is 123-45-6789 for verification and my phone is 555-222-1111.")
        );
        verify(supportMessageService).create(
            eq("privacy-test::clean"),
            eq("mobile"),
            eq("Login issue"),
            eq("I cannot login after password reset. No PII here, just help me regain access.")
        );
    }

    @Test
    void submitMessageReturnsOnlyProcessedPrivacyEvidence() {
        SupportMessage stored = message(
            7L,
            "privacy-test::billing",
            "Billing [EMAIL]",
            "Please call me at [PHONE]",
            true,
            "EMAIL:email,PHONE:phone_number"
        );
        when(supportMessageService.create(
            "privacy-test::billing",
            "webchat",
            "Billing sara@example.com",
            "Please call me at +1 (555) 123-4567"
        )).thenReturn(stored);
        when(supportMessageService.findByCustomerIdPrefix("privacy-test::")).thenReturn(List.of(stored));

        PrivacyDemoService.MessageResult result = service.submitMessage(new PrivacyDemoService.SubmitMessageRequest(
            "privacy-test",
            "billing",
            "webchat",
            "Billing sara@example.com",
            "Please call me at +1 (555) 123-4567"
        ));

        assertThat(result.message().processedSubject()).isEqualTo("Billing [EMAIL]");
        assertThat(result.message().processedMessage()).isEqualTo("Please call me at [PHONE]");
        assertThat(result.message().processedSubject()).doesNotContain("sara@example.com");
        assertThat(result.message().processedMessage()).doesNotContain("+1 (555) 123-4567");
        assertThat(result.message().rawInputWithheld()).isTrue();
        assertThat(result.dashboard().metrics().rawInputWithheld()).isTrue();
    }

    @Test
    void searchReportsSanitizedQueryAndFiltersResultsToCurrentSession() {
        String rawQuery = "find sara@example.com refund";
        SupportMessage sameSession = message(
            10L,
            "privacy-test::refund",
            "Refund request",
            "Order 81273 - my SSN is [SSN] for verification",
            true,
            "SSN:ssn"
        );
        SupportMessage otherSession = message(
            11L,
            "other-session::refund",
            "Refund request",
            "Other customer result",
            false,
            ""
        );
        when(supportMessageService.processForPrivacy(rawQuery)).thenReturn(new SupportMessageService.PrivacyProcessingEvidence(
            "find [EMAIL] refund",
            true,
            1,
            "EMAIL:email",
            "REDACT"
        ));
        when(supportMessageService.semanticSearch(rawQuery, 6)).thenReturn(List.of(otherSession, sameSession));

        PrivacyDemoService.SearchResult result = service.search("privacy-test", rawQuery, 6);

        assertThat(result.processedQuery()).isEqualTo("find [EMAIL] refund");
        assertThat(result.processedQuery()).doesNotContain("sara@example.com");
        assertThat(result.queryPiiDetected()).isTrue();
        assertThat(result.resultCount()).isEqualTo(1);
        assertThat(result.results()).extracting(PrivacyDemoService.PrivacyMessageCard::id).containsExactly(10L);
    }

    private static SupportMessage message(long id,
                                          String customerId,
                                          String processedSubject,
                                          String processedMessage,
                                          boolean piiDetected,
                                          String detectionsSummary) {
        SupportMessage message = new SupportMessage();
        message.setId(id);
        message.setCustomerId(customerId);
        message.setChannel("webchat");
        message.setProcessedSubject(processedSubject);
        message.setProcessedMessage(processedMessage);
        message.setPiiDetected(piiDetected);
        message.setModeApplied("REDACT");
        message.setDetectionsCount(detectionsSummary.isBlank() ? 0 : detectionsSummary.split(",").length);
        message.setDetectionsSummary(detectionsSummary);
        message.setMessageEncryptedOriginal(piiDetected ? "HASH:original" : null);
        message.setCreatedAt(Instant.parse("2026-07-03T12:00:00Z"));
        return message;
    }
}
