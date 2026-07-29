package com.ai.fabric.realapps.agenticresolver.agentic;

import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record SupportCreditReviewRequest(
    @NotBlank @Size(max = 2000) String question,
    @NotNull RefundRequest.ResolutionType resolutionType,
    @NotNull @DecimalMin("0.01") @DecimalMax("1000.00")
    BigDecimal amount,
    @NotBlank @Size(min = 3, max = 500) String reason
) {}
