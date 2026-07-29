package ai.fabric.execution.review.policy;

import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaRegistry;
import ai.fabric.execution.review.auth.ReviewerAuthorizerRegistry;
import ai.fabric.execution.review.continuation.ReviewCorrectionHandlerRegistry;
import ai.fabric.execution.review.continuation.ReviewInformationHandlerRegistry;
import ai.fabric.execution.review.dispatch.ReviewTaskDispatcherRegistry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable startup registry for application-approved review policies.
 */
public final class DefaultReviewPolicyRegistry
    implements ReviewPolicyRegistry {

    private final Map<ReviewPolicyId, RegisteredReviewPolicy> policies;

    public DefaultReviewPolicyRegistry(
        List<ReviewPolicyDefinition> definitions,
        SpecialistJsonSchemaRegistry schemaRegistry,
        CanonicalJsonSupport canonicalJson
    ) {
        this(
            definitions,
            schemaRegistry,
            canonicalJson,
            null,
            null,
            null,
            null
        );
    }

    public DefaultReviewPolicyRegistry(
        List<ReviewPolicyDefinition> definitions,
        SpecialistJsonSchemaRegistry schemaRegistry,
        CanonicalJsonSupport canonicalJson,
        ReviewerAuthorizerRegistry authorizers,
        ReviewTaskDispatcherRegistry dispatchers,
        ReviewCorrectionHandlerRegistry correctionHandlers,
        ReviewInformationHandlerRegistry informationHandlers
    ) {
        Objects.requireNonNull(
            schemaRegistry,
            "schemaRegistry is required"
        );
        Objects.requireNonNull(canonicalJson, "canonicalJson is required");
        Map<ReviewPolicyId, RegisteredReviewPolicy> loaded =
            new LinkedHashMap<>();
        for (ReviewPolicyDefinition definition :
            definitions == null
                ? List.<ReviewPolicyDefinition>of()
                : definitions) {
            Objects.requireNonNull(
                definition,
                "review policy must not be null"
            );
            validateSchemas(definition, schemaRegistry);
            validateExtensions(
                definition,
                authorizers,
                dispatchers,
                correctionHandlers,
                informationHandlers
            );
            RegisteredReviewPolicy registered =
                new RegisteredReviewPolicy(
                    definition,
                    policyHash(definition, canonicalJson)
                );
            if (loaded.putIfAbsent(definition.id(), registered) != null) {
                throw new IllegalStateException(
                    "Duplicate review policy: " + definition.id()
                );
            }
        }
        validateEscalationTargets(loaded);
        this.policies = Collections.unmodifiableMap(loaded);
    }

    private String policyHash(
        ReviewPolicyDefinition definition,
        CanonicalJsonSupport canonicalJson
    ) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("id", definition.id().toString());
        manifest.put("type", definition.type().name());
        manifest.put(
            "allowedDecisions",
            definition.allowedDecisions().stream()
                .map(Enum::name)
                .sorted()
                .toList()
        );
        manifest.put(
            "reviewerAuthorizerId",
            definition.reviewerAuthorizerId()
        );
        manifest.put("dispatcherId", definition.dispatcherId());
        manifest.put(
            "requiredReviewerScopes",
            definition.requiredReviewerScopes().stream().sorted().toList()
        );
        manifest.put(
            "separationOfDuty",
            definition.separationOfDuty()
        );
        manifest.put("taskTtl", definition.taskTtl().toString());
        putOptional(
            manifest,
            "correctionSchemaId",
            definition.correctionSchemaId()
        );
        putOptional(
            manifest,
            "correctionHandlerId",
            definition.correctionHandlerId()
        );
        putOptional(
            manifest,
            "informationRequestSchemaId",
            definition.informationRequestSchemaId()
        );
        putOptional(
            manifest,
            "informationResponseSchemaId",
            definition.informationResponseSchemaId()
        );
        putOptional(
            manifest,
            "informationHandlerId",
            definition.informationHandlerId()
        );
        putOptional(
            manifest,
            "escalationPolicyId",
            definition.escalationPolicyId()
        );
        return canonicalJson.hashValue(manifest);
    }

    private void putOptional(
        Map<String, Object> manifest,
        String field,
        Object value
    ) {
        if (value != null) {
            manifest.put(field, value.toString());
        }
    }

    private void validateExtensions(
        ReviewPolicyDefinition definition,
        ReviewerAuthorizerRegistry authorizers,
        ReviewTaskDispatcherRegistry dispatchers,
        ReviewCorrectionHandlerRegistry correctionHandlers,
        ReviewInformationHandlerRegistry informationHandlers
    ) {
        if (authorizers != null) {
            authorizers.require(definition.reviewerAuthorizerId());
        }
        if (dispatchers != null) {
            dispatchers.require(definition.dispatcherId());
        }
        if (definition.correctionHandlerId() != null
            && correctionHandlers != null) {
            correctionHandlers.require(definition.correctionHandlerId());
        }
        if (definition.informationHandlerId() != null
            && informationHandlers != null) {
            informationHandlers.require(definition.informationHandlerId());
        }
    }

    @Override
    public java.util.Optional<RegisteredReviewPolicy> find(
        ReviewPolicyId id
    ) {
        return java.util.Optional.ofNullable(policies.get(id));
    }

    @Override
    public List<RegisteredReviewPolicy> list() {
        return List.copyOf(policies.values());
    }

    private void validateSchemas(
        ReviewPolicyDefinition definition,
        SpecialistJsonSchemaRegistry schemaRegistry
    ) {
        if (definition.correctionSchemaId() != null) {
            schemaRegistry.require(
                definition.correctionSchemaId(),
                SpecialistSchemaDirection.INPUT
            );
        }
        if (definition.informationRequestSchemaId() != null) {
            schemaRegistry.require(
                definition.informationRequestSchemaId(),
                SpecialistSchemaDirection.INPUT
            );
        }
        if (definition.informationResponseSchemaId() != null) {
            schemaRegistry.require(
                definition.informationResponseSchemaId(),
                SpecialistSchemaDirection.INPUT
            );
        }
    }

    private void validateEscalationTargets(
        Map<ReviewPolicyId, RegisteredReviewPolicy> loaded
    ) {
        for (RegisteredReviewPolicy policy : loaded.values()) {
            ReviewPolicyId escalation =
                policy.definition().escalationPolicyId();
            if (escalation == null) {
                continue;
            }
            if (escalation.equals(policy.id())) {
                throw new IllegalStateException(
                    "Review policy cannot escalate to itself: " + policy.id()
                );
            }
            if (!loaded.containsKey(escalation)) {
                throw new IllegalStateException(
                    "Review escalation policy is not registered: "
                        + escalation
                );
            }
        }
    }
}
