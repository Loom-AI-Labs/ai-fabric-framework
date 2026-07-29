package com.ai.fabric.realapps.agenticresolver.agentic;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;

public record BillingAmountResponse(
    @DecimalMin(value = "0.01")
    @DecimalMax(value = "1000000")
    BigDecimal amount
) {}
