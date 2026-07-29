package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

final class BillingResolutionInputContinuation
    implements SpecialistInputContinuation<JsonNode> {

    static final String ID = "billing-resolution-input@1";
    static final SpecialistSchemaId AMOUNT_RESPONSE_SCHEMA =
        SpecialistSchemaId.parse("billing-amount-response@1");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Class<JsonNode> inputType() {
        return JsonNode.class;
    }

    @Override
    public Set<SpecialistSchemaId> responseSchemas() {
        return Set.of(AMOUNT_RESPONSE_SCHEMA);
    }

    @Override
    public Optional<SpecialistInputRequirement> requiredInput(
        JsonNode input
    ) {
        JsonNode amount = input != null ? input.get("amount") : null;
        if (amount != null && !amount.isNull()) {
            return Optional.empty();
        }
        return Optional.of(new SpecialistInputRequirement(
            "MISSING_BILLING_AMOUNT",
            "What billing amount should be assessed?",
            AMOUNT_RESPONSE_SCHEMA,
            Duration.ofMinutes(10),
            3
        ));
    }

    @Override
    public JsonNode resume(
        JsonNode originalInput,
        SpecialistInputRequirement requirement,
        JsonNode response
    ) {
        if (!(originalInput instanceof ObjectNode)) {
            throw new IllegalArgumentException(
                "Billing assessment input must be an object"
            );
        }
        ObjectNode resumed = ((ObjectNode) originalInput).deepCopy();
        resumed.set("amount", response.required("amount").deepCopy());
        return resumed;
    }

    @Override
    public JsonNode snapshot(JsonNode input) {
        return input.deepCopy();
    }
}
