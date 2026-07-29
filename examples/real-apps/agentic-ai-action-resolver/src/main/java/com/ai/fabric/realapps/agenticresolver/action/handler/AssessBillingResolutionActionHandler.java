package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionAllowed;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.ActionFacts;
import ai.fabric.intent.action.annotation.Param;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@AIAction(
    name = "assess_billing_resolution",
    description = "Read the authoritative policy outcome for a refund or account-credit amount without creating a billing resolution",
    category = "account-resolver",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false,
    readActionResolutionEligible = true
)
public class AssessBillingResolutionActionHandler {

    private final AccountResolutionService accountResolutionService;

    public AssessBillingResolutionActionHandler(
        AccountResolutionService accountResolutionService
    ) {
        this.accountResolutionService = accountResolutionService;
    }

    @ActionAllowed
    public boolean allowed(ActionContext context) {
        return context != null
            && context.userId() != null
            && !context.userId().isBlank();
    }

    @ActionExecute
    public ActionResult execute(
        @Param(
            value = "amount",
            required = true,
            description = "Billing amount to assess; must be greater than zero",
            min = 0
        ) BigDecimal amount,
        @Param(
            value = "resolutionType",
            required = true,
            description = "REFUND or ACCOUNT_CREDIT",
            allowedValues = {"REFUND", "ACCOUNT_CREDIT"}
        ) RefundRequest.ResolutionType resolutionType,
        ActionContext context
    ) {
        AccountResolutionService.BillingResolutionAssessment assessment =
            accountResolutionService.assessBillingResolution(
                amount,
                resolutionType
            );
        return ActionResult.builder()
            .success(true)
            .message("Billing-resolution policy assessed")
            .data(ActionResultContracts.object(assessmentFields(assessment)))
            .build();
    }

    @ActionFacts
    public Map<String, Object> facts(
        ActionResult result,
        ActionContext context
    ) {
        if (result == null || result.getData() == null) {
            throw new IllegalArgumentException(
                "Authoritative billing assessment is required"
            );
        }
        Map<String, Object> assessment = result.getData().toMap();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("factSource", "billing_resolution_policy");
        facts.put(
            "resolutionType",
            required(assessment, "resolutionType", String.class)
        );
        facts.put("amount", requiredNumber(assessment, "amount"));
        facts.put(
            "decision",
            required(assessment, "decision", String.class)
        );
        facts.put(
            "expectedStatus",
            required(assessment, "expectedStatus", String.class)
        );
        facts.put(
            "automaticLimit",
            requiredNumber(assessment, "automaticLimit")
        );
        facts.put(
            "explanation",
            required(assessment, "explanation", String.class)
        );
        return facts;
    }

    private Map<String, Object> assessmentFields(
        AccountResolutionService.BillingResolutionAssessment assessment
    ) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("resolutionType", assessment.resolutionType());
        fields.put("amount", assessment.amount());
        fields.put("decision", assessment.decision());
        fields.put("expectedStatus", assessment.expectedStatus());
        fields.put("automaticLimit", assessment.automaticLimit());
        fields.put("explanation", assessment.explanation());
        return fields;
    }

    private <T> T required(
        Map<String, Object> values,
        String name,
        Class<T> type
    ) {
        Object value = values.get(name);
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException(
                "Authoritative billing assessment is missing " + name
            );
        }
        return type.cast(value);
    }

    private Number requiredNumber(
        Map<String, Object> values,
        String name
    ) {
        return required(values, name, Number.class);
    }
}
