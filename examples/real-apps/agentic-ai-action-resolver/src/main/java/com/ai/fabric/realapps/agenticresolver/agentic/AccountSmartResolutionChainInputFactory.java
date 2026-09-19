package com.ai.fabric.realapps.agenticresolver.agentic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Prepares the public request for the schema-backed declarative chain. */
@Component
final class AccountSmartResolutionChainInputFactory {

    static final String ACCOUNT_READ_QUESTION =
        "Inspect only the current account readiness and explain any blockers. "
            + "Do not assess a refund or account credit.";
    static final String BILLING_ASSESSMENT_QUESTION =
        "Assess only the supplied billing resolution against approved policy. "
            + "Do not inspect account readiness.";

    private final ObjectMapper objectMapper;

    AccountSmartResolutionChainInputFactory(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
    }

    JsonNode create(AccountDelegationCoordinatorRequest input) {
        Objects.requireNonNull(input, "input is required");
        ObjectNode chainInput = objectMapper.createObjectNode();
        chainInput.put("question", input.question());
        chainInput.put("billingInputState", billingInputState(input));
        chainInput.put("accountReadQuestion", ACCOUNT_READ_QUESTION);
        chainInput.put(
            "billingAssessmentQuestion",
            BILLING_ASSESSMENT_QUESTION
        );
        if (input.resolutionType() != null) {
            chainInput.put("resolutionType", input.resolutionType().name());
        }
        if (input.amount() != null) {
            chainInput.put("amount", input.amount());
            chainInput.put(
                "amountText",
                input.amount().stripTrailingZeros().toPlainString()
            );
        }
        return chainInput;
    }

    private String billingInputState(
        AccountDelegationCoordinatorRequest input
    ) {
        if (input.resolutionType() == null && input.amount() == null) {
            return "BOTH_MISSING";
        }
        if (input.resolutionType() == null) {
            return "RESOLUTION_TYPE_MISSING";
        }
        if (input.amount() == null) {
            return "AMOUNT_MISSING";
        }
        return "COMPLETE";
    }
}
