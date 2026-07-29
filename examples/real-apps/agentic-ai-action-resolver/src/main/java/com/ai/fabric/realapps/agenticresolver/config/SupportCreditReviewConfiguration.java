package com.ai.fabric.realapps.agenticresolver.config;

import ai.fabric.execution.review.auth.ReviewerAuthorization;
import ai.fabric.execution.review.auth.ReviewerAuthorizer;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandler;
import ai.fabric.execution.review.continuation.ReviewCorrectionOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationHandler;
import ai.fabric.execution.review.continuation.ReviewInformationRequestOutcome;
import ai.fabric.execution.review.continuation.ReviewInformationSubmissionOutcome;
import ai.fabric.execution.review.dispatch.ReviewDispatchResult;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcher;
import ai.fabric.execution.review.policy.ReviewPolicyDefinition;
import ai.fabric.execution.review.policy.ReviewType;
import com.ai.fabric.realapps.agenticresolver.agentic.review.SupportCreditCorrectionProposalService;
import com.ai.fabric.realapps.agenticresolver.agentic.review.SupportCreditReviewPolicies;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupportCreditReviewAccessProperties.class)
@ConditionalOnProperty(
    prefix = "ai.execution.reviews",
    name = "enabled",
    havingValue = "true"
)
public class SupportCreditReviewConfiguration {

    @Bean
    ReviewPolicyDefinition supportCreditReviewPolicy() {
        return new ReviewPolicyDefinition(
            SupportCreditReviewPolicies.STANDARD,
            ReviewType.OPERATIONAL_REVIEW,
            Set.of(
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .APPROVE,
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .REJECT,
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .CORRECT,
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .REQUEST_INFORMATION,
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .ESCALATE
            ),
            SupportCreditReviewPolicies.REVIEWER_AUTHORIZER,
            SupportCreditReviewPolicies.DISPATCHER,
            Set.of(SupportCreditReviewPolicies.REVIEW_SCOPE),
            true,
            Duration.ofHours(24),
            SupportCreditReviewPolicies.CORRECTION_SCHEMA,
            SupportCreditReviewPolicies.CORRECTION_HANDLER,
            SupportCreditReviewPolicies.INFORMATION_REQUEST_SCHEMA,
            SupportCreditReviewPolicies.INFORMATION_RESPONSE_SCHEMA,
            SupportCreditReviewPolicies.INFORMATION_HANDLER,
            SupportCreditReviewPolicies.SENIOR
        );
    }

    @Bean
    ReviewPolicyDefinition seniorSupportCreditReviewPolicy() {
        return new ReviewPolicyDefinition(
            SupportCreditReviewPolicies.SENIOR,
            ReviewType.OPERATIONAL_REVIEW,
            Set.of(
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .APPROVE,
                ai.fabric.execution.review.decision.ReviewDecisionType
                    .REJECT
            ),
            SupportCreditReviewPolicies.REVIEWER_AUTHORIZER,
            SupportCreditReviewPolicies.DISPATCHER,
            Set.of(
                SupportCreditReviewPolicies.REVIEW_SCOPE,
                SupportCreditReviewPolicies.SENIOR_REVIEW_SCOPE
            ),
            true,
            Duration.ofHours(24),
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Bean
    ReviewerAuthorizer supportCreditReviewerAuthorizer() {
        return new ReviewerAuthorizer() {
            @Override
            public String id() {
                return SupportCreditReviewPolicies.REVIEWER_AUTHORIZER;
            }

            @Override
            public ReviewerAuthorization authorize(
                ai.fabric.execution.review.auth
                    .ReviewAuthorizationRequest request,
                ai.fabric.execution.review.TrustedReviewerContext reviewer
            ) {
                if (!request.task().policyId().equals(
                        SupportCreditReviewPolicies.STANDARD
                    )
                    && !request.task().policyId().equals(
                        SupportCreditReviewPolicies.SENIOR
                    )) {
                    return ReviewerAuthorization.deny(
                        "REVIEW_POLICY_NOT_SUPPORTED"
                    );
                }
                return ReviewerAuthorization.allow();
            }
        };
    }

    @Bean
    ReviewTaskDispatcher localReviewInboxDispatcher() {
        return new ReviewTaskDispatcher() {
            @Override
            public String id() {
                return SupportCreditReviewPolicies.DISPATCHER;
            }

            @Override
            public ReviewDispatchResult dispatch(
                ai.fabric.execution.review.dispatch
                    .ReviewDispatchRequest request
            ) {
                return ReviewDispatchResult.accepted(
                    "local-inbox:" + request.task().taskId()
                );
            }
        };
    }

    @Bean
    ReviewCorrectionHandler supportCreditCorrectionHandler(
        SupportCreditCorrectionProposalService proposalService
    ) {
        return new ReviewCorrectionHandler() {
            @Override
            public String id() {
                return SupportCreditReviewPolicies.CORRECTION_HANDLER;
            }

            @Override
            public ReviewCorrectionOutcome correct(
                ai.fabric.execution.review.continuation
                    .ReviewCorrectionContext context
            ) {
                return ReviewCorrectionOutcome.successor(
                    proposalService.propose(context).receiptId()
                );
            }
        };
    }

    @Bean
    ReviewInformationHandler supportCreditInformationHandler() {
        return new ReviewInformationHandler() {
            @Override
            public String id() {
                return SupportCreditReviewPolicies.INFORMATION_HANDLER;
            }

            @Override
            public ReviewInformationRequestOutcome requestInformation(
                ai.fabric.execution.review.continuation
                    .ReviewInformationRequestContext context
            ) {
                return new ReviewInformationRequestOutcome(
                    "Additional source information was requested."
                );
            }

            @Override
            public ReviewInformationSubmissionOutcome receiveInformation(
                ai.fabric.execution.review.continuation
                    .ReviewInformationSubmissionContext context
            ) {
                return new ReviewInformationSubmissionOutcome(
                    "The incident reference is available to the authorized reviewer."
                );
            }
        };
    }
}
