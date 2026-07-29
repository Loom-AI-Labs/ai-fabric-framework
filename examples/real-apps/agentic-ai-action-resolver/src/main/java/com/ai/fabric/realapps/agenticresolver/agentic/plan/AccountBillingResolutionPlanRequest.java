package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record AccountBillingResolutionPlanRequest(
    @NotBlank @Size(max = 1_200) String question,
    @NotNull RefundRequest.ResolutionType resolutionType,
    @DecimalMin(value = "0.01") @DecimalMax(value = "1000000")
    BigDecimal amount
) {
    public AccountBillingResolutionPlanRequest {
        question = question != null ? question.trim() : null;
    }
}
