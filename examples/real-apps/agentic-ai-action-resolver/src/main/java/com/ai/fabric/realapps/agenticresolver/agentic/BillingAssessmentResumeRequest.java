package com.ai.fabric.realapps.agenticresolver.agentic;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BillingAssessmentResumeRequest(
    @NotBlank String invocationId,
    @NotBlank String requestId,
    @NotNull @Valid BillingAmountResponse response
) {}
