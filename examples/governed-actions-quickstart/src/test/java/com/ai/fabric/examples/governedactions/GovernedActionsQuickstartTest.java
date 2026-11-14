package com.ai.fabric.examples.governedactions;

import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.intent.action.ActionResult;
import com.ai.fabric.examples.governedactions.action.GetAccountAction;
import com.ai.fabric.examples.governedactions.action.UpdateEmailAction;
import com.ai.fabric.examples.governedactions.provider.QuickstartAIProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GovernedActionsQuickstartTest {

    @Test
    void readActionReturnsAccount() {

        GetAccountAction action = new GetAccountAction();

        ActionResult result = action.execute();

        assertTrue(result.isSuccess());
        assertEquals("Account retrieved", result.getMessage());
    }

    @Test
    void writeActionProvidesConfirmationAndExecutes() {

        UpdateEmailAction action = new UpdateEmailAction();

        String confirmation = action.confirm("test@gmail.com");

        assertEquals(
                "Change your email to test@gmail.com?",
                confirmation
        );

        ActionResult result = action.execute("test@gmail.com");

        assertTrue(result.isSuccess());
        assertEquals(
                "Email updated to test@gmail.com",
                result.getMessage()
        );
    }

    @Test
    void providerExtractsUpdateEmailAction() {

        QuickstartAIProvider provider = new QuickstartAIProvider();

        AIGenerationRequest request = AIGenerationRequest.builder()
                .prompt(
                        "User's question is: " +
                                "(Change my email to test@gmail.com)"
                )
                .build();

        AIGenerationResponse response =
                provider.generateContent(request);

        assertNotNull(response);
        assertNotNull(response.getContent());

        assertTrue(
                response.getContent().contains("\"type\": \"ACTION\"")
        );

        assertTrue(
                response.getContent().contains(
                        "\"action\": \"update_email\""
                )
        );

        assertTrue(
                response.getContent().contains(
                        "\"email\": \"test@gmail.com\""
                )
        );
    }

    @Test
    void providerRecognizesPositiveConfirmationWithPendingAction() {

        QuickstartAIProvider provider = new QuickstartAIProvider();

        AIGenerationRequest request = AIGenerationRequest.builder()
                .prompt("""
                        User's question is: (PENDING ACTION (requires confirmation):
                        - action=update_email

                        yes)
                        """)
                .build();

        AIGenerationResponse response =
                provider.generateContent(request);

        assertNotNull(response);

        assertTrue(
                response.getContent().contains(
                        "\"type\": \"CONFIRMATION_POSITIVE\""
                )
        );
    }

    @Test
    void providerRecognizesNegativeConfirmationWithPendingAction() {

        QuickstartAIProvider provider = new QuickstartAIProvider();

        AIGenerationRequest request = AIGenerationRequest.builder()
                .prompt("""
                        User's question is: (PENDING ACTION (requires confirmation):
                        - action=update_email

                        no)
                        """)
                .build();

        AIGenerationResponse response =
                provider.generateContent(request);

        assertNotNull(response);

        assertTrue(
                response.getContent().contains(
                        "\"type\": \"CONFIRMATION_NEGATIVE\""
                )
        );
    }

    @Test
    void invalidEmailIsNotExtractedAsParameter() {

        QuickstartAIProvider provider = new QuickstartAIProvider();

        AIGenerationRequest request = AIGenerationRequest.builder()
                .prompt(
                        "User's question is: " +
                                "(Change my email to invalid-email)"
                )
                .build();

        AIGenerationResponse response =
                provider.generateContent(request);

        assertNotNull(response);

        assertTrue(
                response.getContent().contains(
                        "\"action\": \"update_email\""
                )
        );

        assertFalse(
                response.getContent().contains(
                        "\"email\": \"invalid-email\""
                )
        );
    }
}