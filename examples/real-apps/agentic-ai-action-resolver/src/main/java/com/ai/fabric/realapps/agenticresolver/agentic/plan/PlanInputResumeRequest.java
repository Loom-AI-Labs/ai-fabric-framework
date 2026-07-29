package com.ai.fabric.realapps.agenticresolver.agentic.plan;

import com.ai.fabric.realapps.agenticresolver.agentic.BillingAmountResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PlanInputResumeRequest(
    @NotBlank String executionId,
    @NotBlank String requestId,
    @NotNull @Valid BillingAmountResponse response
) {}
