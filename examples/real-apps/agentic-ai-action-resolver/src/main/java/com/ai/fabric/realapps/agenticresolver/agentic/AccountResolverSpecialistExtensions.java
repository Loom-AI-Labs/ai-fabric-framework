package com.ai.fabric.realapps.agenticresolver.agentic;

import ai.fabric.execution.specialist.manifest.SpecialistDirectOutputProjector;
import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidator;
import ai.fabric.execution.specialist.manifest.SpecialistGroundingValidator;
import ai.fabric.execution.input.SpecialistInputContinuation;
import com.fasterxml.jackson.databind.JsonNode;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountResolverSpecialistExtensions {

    public static final String GROUNDING_VALIDATOR =
        "account-readiness-grounding@1";
    public static final String DIRECT_PROJECTOR =
        "account-readiness-projector@1";
    public static final String FINAL_VALIDATOR =
        "account-readiness-consistency@1";
    public static final String BILLING_GROUNDING_VALIDATOR =
        "billing-resolution-grounding@1";
    public static final String BILLING_DIRECT_PROJECTOR =
        "billing-resolution-projector@1";
    public static final String BILLING_FINAL_VALIDATOR =
        "billing-resolution-consistency@1";

    @Bean
    AccountReadinessProjection accountReadinessProjection(
        ObjectMapper objectMapper,
        AccountResolutionService accountResolutionService
    ) {
        return new AccountReadinessProjection(
            objectMapper,
            accountResolutionService
        );
    }

    @Bean
    SpecialistGroundingValidator accountReadinessGroundingValidator(
        AccountReadinessProjection projection
    ) {
        return SpecialistGroundingValidator.named(
            GROUNDING_VALIDATOR,
            context -> projection.validateGrounding(context.result())
        );
    }

    @Bean
    SpecialistDirectOutputProjector accountReadinessProjector(
        AccountReadinessProjection projection
    ) {
        return SpecialistDirectOutputProjector.named(
            DIRECT_PROJECTOR,
            (result, evidence) -> projection.project(result)
        );
    }

    @Bean
    SpecialistFinalOutputValidator accountReadinessConsistencyValidator(
        AccountReadinessProjection projection
    ) {
        return SpecialistFinalOutputValidator.named(
            FINAL_VALIDATOR,
            context -> projection.validateFinalOutput(
                context.output(),
                context.sourceResult()
            )
        );
    }

    @Bean
    SpecialistInputContinuation<JsonNode>
    billingResolutionInputContinuation() {
        return new BillingResolutionInputContinuation();
    }

    @Bean
    BillingResolutionProjection billingResolutionProjection(
        ObjectMapper objectMapper
    ) {
        return new BillingResolutionProjection(objectMapper);
    }

    @Bean
    SpecialistGroundingValidator billingResolutionGroundingValidator(
        BillingResolutionProjection projection
    ) {
        return SpecialistGroundingValidator.named(
            BILLING_GROUNDING_VALIDATOR,
            context -> projection.validateGrounding(context.result())
        );
    }

    @Bean
    SpecialistDirectOutputProjector billingResolutionProjector(
        BillingResolutionProjection projection
    ) {
        return SpecialistDirectOutputProjector.named(
            BILLING_DIRECT_PROJECTOR,
            (result, evidence) -> projection.project(result)
        );
    }

    @Bean
    SpecialistFinalOutputValidator billingResolutionConsistencyValidator(
        BillingResolutionProjection projection
    ) {
        return SpecialistFinalOutputValidator.named(
            BILLING_FINAL_VALIDATOR,
            context -> projection.validateFinalOutput(
                context.output(),
                context.sourceResult()
            )
        );
    }
}
