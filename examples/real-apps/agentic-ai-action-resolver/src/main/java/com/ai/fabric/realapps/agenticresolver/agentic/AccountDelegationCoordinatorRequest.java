package com.ai.fabric.realapps.agenticresolver.agentic;

import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Public routing input. Identity and authority remain backend-owned.
 */
public record AccountDelegationCoordinatorRequest(
    @NotBlank @Size(max = 2_000) String question,
    RefundRequest.ResolutionType resolutionType,
    @DecimalMin("0.01") @DecimalMax("1000000") BigDecimal amount
) {
    public AccountDelegationCoordinatorRequest {
        question = question != null ? question.trim() : null;
    }
}
