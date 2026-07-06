package com.ai.fabric.realapps.behavior.service;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.llm.structured.DefaultStructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgenticUiComposerServiceTest {

    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AgenticUiComposerService service = new AgenticUiComposerService(
        aiCoreService,
        new DefaultStructuredJsonCallExecutor(new StructuredJsonExtractor(), objectMapper),
        objectMapper
    );

    @Test
    void composesAllowlistedComponentsAndFillsTrustedProps() {
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder()
                .model("test-layout-model")
                .content("""
                    {
                      "layout": "custom-workbench",
                      "summary": "Show the save motion.",
                      "components": [
                        {"name": "RISK_SUMMARY_CARD", "reason": "High churn risk."},
                        {"name": "ARBITRARY_WIDGET", "reason": "Should be ignored."},
                        {"name": "RETENTION_OFFER_PANEL", "reason": "Needs confirmation."}
                      ]
                    }
                    """)
                .build());

        AgenticUiComposerService.AgenticUiResponse response = service.compose(sampleResult("RETENTION_OFFER"));

        assertThat(response.userId()).isEqualTo("user-1001");
        assertThat(response.plan().layout()).isEqualTo("custom-workbench");
        assertThat(response.plan().source()).isEqualTo("llm");
        assertThat(response.plan().model()).isEqualTo("test-layout-model");
        assertThat(response.plan().components())
            .extracting(AgenticUiComposerService.AgenticUiComponent::type)
            .containsExactly("RISK_SUMMARY_CARD", "RETENTION_OFFER_PANEL");

        AgenticUiComposerService.AgenticUiComponent risk = response.plan().components().get(0);
        assertThat(risk.title()).isEqualTo("Risk Summary Card");
        assertThat(risk.rationale()).isEqualTo("High churn risk.");
        assertThat(risk.props()).containsEntry("segment", "at_risk");
        assertThat(risk.props()).containsEntry("churnRisk", 0.91);

        AgenticUiComposerService.AgenticUiComponent offer = response.plan().components().get(1);
        assertThat(offer.title()).isEqualTo("Retention Offer Panel");
        assertThat(offer.props()).containsEntry("discountPercent", 25);
        assertThat(offer.props()).containsEntry("confirmationRequired", true);
    }

    @Test
    void fallsBackWhenLlmDoesNotReturnUsableJson() {
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder()
                .model("broken-model")
                .content("not json")
                .build());

        AgenticUiComposerService.AgenticUiResponse response = service.compose(sampleResult("ENGINEERING_ESCALATION"));

        assertThat(response.plan().source()).contains("fallback");
        assertThat(response.plan().model()).isEqualTo("fallback");
        assertThat(response.plan().components())
            .extracting(AgenticUiComposerService.AgenticUiComponent::type)
            .containsExactly("PRODUCT_ESCALATION_PANEL", "EVENT_TIMELINE", "RECOMMENDED_ACTION_CARD");
    }

    private static BehaviorDemoScenarioService.BehaviorScenarioResult sampleResult(String actionFamily) {
        BehaviorDemoScenarioService.DemoScenarioSummary scenario = new BehaviorDemoScenarioService.DemoScenarioSummary(
            "billing-cancellation-risk",
            "user-1001",
            "user-1001",
            "acct-1001",
            "Acme Finance",
            "pro",
            "Billing-driven cancellation risk",
            "Repeated failed renewals and cancellation intent.",
            "Spot churn before the renewal is lost.",
            "PAYMENT_FAILED",
            "Renewal payment failed in the billing service.",
            25,
            62,
            2,
            3,
            actionFamily,
            2,
            null
        );
        BehaviorDemoScenarioService.InsightSummary insight = new BehaviorDemoScenarioService.InsightSummary(
            "user-1001",
            "at_risk",
            "CHURNING",
            -0.72,
            0.91,
            "Repeated failed payments",
            "RAPIDLY_DECLINING",
            List.of("payment_signals:2", "cancel_signals:1"),
            List.of("Offer retention credit", "Assign CSM outreach"),
            0.82,
            "behavior-local",
            12L,
            true,
            LocalDateTime.now()
        );
        BehaviorDemoScenarioService.BehaviorEventSummary event = new BehaviorDemoScenarioService.BehaviorEventSummary(
            1L,
            "user-1001",
            "PAYMENT_FAILED",
            LocalDateTime.now(),
            "{\"reason\":\"card_declined\",\"invoiceStatus\":\"past_due\"}",
            "billing-service"
        );
        RetentionStudioService.RetentionReviewResult review = new RetentionStudioService.RetentionReviewResult(
            "acct-1001",
            "user-1001",
            "HIGH",
            actionFamily,
            List.of("insight-acct-1001-user-1001", "plan-pro"),
            "Offer retention credit and assign CSM outreach.",
            "High risk qualifies for a confirmation-gated retention offer."
        );
        BehaviorDemoScenarioService.RetentionOfferDemoResult preview = new BehaviorDemoScenarioService.RetentionOfferDemoResult(
            "user-1001",
            "acct-1001",
            "Acme Finance",
            "create_retention_offer",
            "Create 25% retention offer for Acme Finance?",
            new RetentionStudioService.RetentionOfferResult(false, true, "Confirm retention offer.", null, Map.of(
                "discountPercent", 25,
                "policyDecision", "CONFIRMATION_REQUIRED"
            ))
        );
        return new BehaviorDemoScenarioService.BehaviorScenarioResult(
            scenario,
            insight,
            List.of(event),
            review,
            preview
        );
    }
}
