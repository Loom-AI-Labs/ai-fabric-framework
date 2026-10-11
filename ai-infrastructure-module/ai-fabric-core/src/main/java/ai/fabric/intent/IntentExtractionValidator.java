package ai.fabric.intent;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic validator for intent extraction responses.
 *
 * <p>Validation is system-fact driven and must not involve additional LLM calls.</p>
 */
@Component
@RequiredArgsConstructor
public class IntentExtractionValidator {

    private final AIActionRegistry actionHandlerRegistry;

    @Autowired(required = false)
    private ObjectProvider<KnowledgeBaseOverviewService> knowledgeBaseOverviewServiceProvider;

    public ValidationResult validate(MultiIntentResponse response) {
        return validate(response, null);
    }

    /**
     * Validate an extraction response using optional original-query context.
     *
     * <p>This method must remain deterministic and must not call any LLMs. The original query is only used
     * for contract-level checks (e.g., a hinted {@code relationship_query: ...} message that appears to
     * include additional, non-relational instructions).</p>
     */
    public ValidationResult validate(MultiIntentResponse response, String originalQuery) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (response == null) {
            issues.add(ValidationIssue.error(IssueCode.RESPONSE_NULL, -1, null, "Response is null"));
            return toResult(issues);
        }

        if (response.getIntents() == null || response.getIntents().isEmpty()) {
            issues.add(ValidationIssue.warn(IssueCode.RESPONSE_EMPTY_INTENTS, -1, "intents", "Response contains no intents"));
            return toResult(issues);
        }

        for (int i = 0; i < response.getIntents().size(); i++) {
            validateIntent(
                response.getIntents().get(i),
                i,
                issues,
                originalQuery,
                response.getIntents().size() > 1
            );
        }

        return toResult(issues);
    }

    private void validateIntent(Intent intent,
                                int index,
                                List<ValidationIssue> issues,
                                String originalQuery,
                                boolean compound) {
        if (intent == null) {
            issues.add(ValidationIssue.error(IssueCode.INTENT_NULL, index, null, "Intent is null"));
            return;
        }

        if (!intent.hasValidType()) {
            issues.add(ValidationIssue.error(IssueCode.INTENT_MISSING_TYPE, index, "type", "Missing or invalid type"));
        }

        // Provider-agnostic tolerance: OUT_OF_SCOPE intents may omit intent/action names.
        if (!intent.hasMeaningfulName() && intent.getType() != IntentType.OUT_OF_SCOPE) {
            issues.add(ValidationIssue.error(IssueCode.INTENT_MISSING_NAME, index, "intent", "Missing intent name (intent or action field)"));
        }

        if (intent.getRequiresRetrieval() == null) {
            issues.add(ValidationIssue.error(
                IssueCode.INTENT_MISSING_RETRIEVAL_DECISION,
                index,
                "requiresRetrieval",
                "Missing requiresRetrieval semantic decision"
            ));
        }
        if (intent.getRequiresGeneration() == null) {
            issues.add(ValidationIssue.error(
                IssueCode.INTENT_MISSING_GENERATION_DECISION,
                index,
                "requiresGeneration",
                "Missing requiresGeneration semantic decision"
            ));
        }
        if (intent.getRequiresTargetResolution() == null) {
            issues.add(ValidationIssue.error(
                IssueCode.INTENT_MISSING_TARGET_DECISION,
                index,
                "requiresTargetResolution",
                "Missing requiresTargetResolution semantic decision"
            ));
        }
        if (compound && !StringUtils.hasText(intent.getObligationId())) {
            issues.add(ValidationIssue.error(
                IssueCode.INTENT_MISSING_OBLIGATION_ID,
                index,
                "obligationId",
                "Compound intent is missing an obligationId"
            ));
        }
        if (intent.getMissingInformation() != null
            && !intent.getMissingInformation().isEmpty()
            && !StringUtils.hasText(intent.getClarificationQuestion())) {
            issues.add(ValidationIssue.error(
                IssueCode.INTENT_MISSING_CLARIFICATION_QUESTION,
                index,
                "clarificationQuestion",
                "Intent declares missing information without a clarificationQuestion"
            ));
        }

        if (intent.getType() == IntentType.ACTION) {
            validateActionIntent(intent, index, issues, originalQuery);
        } else if (intent.getType() == IntentType.INFORMATION) {
            validateInformationIntent(intent, index, issues);
        } else if (intent.getType() == IntentType.OUT_OF_SCOPE) {
            validateOutOfScopeIntent(intent, index, issues);
        }
    }

    private void validateOutOfScopeIntent(Intent intent,
                                          int index,
                                          List<ValidationIssue> issues) {
        if (!StringUtils.hasText(intent.getDirectAnswer())) {
            issues.add(ValidationIssue.error(
                IssueCode.OUT_OF_SCOPE_RESPONSE_MISSING,
                index,
                "directAnswer",
                "OUT_OF_SCOPE intent is missing a model-authored customer-safe directAnswer"
            ));
        }
        if (Boolean.TRUE.equals(intent.getRequiresRetrieval())
            || Boolean.TRUE.equals(intent.getRequiresGeneration())
            || Boolean.TRUE.equals(intent.getRequiresTargetResolution())) {
            issues.add(ValidationIssue.error(
                IssueCode.OUT_OF_SCOPE_EXECUTION_NOT_ALLOWED,
                index,
                "requiresRetrieval",
                "OUT_OF_SCOPE intent cannot request retrieval, generation, or target resolution"
            ));
        }
    }

    private void validateActionIntent(Intent intent, int index, List<ValidationIssue> issues, String originalQuery) {
        String actionName = StringUtils.hasText(intent.getAction())
            ? intent.getAction()
            : intent.getIntent();

        if (!StringUtils.hasText(actionName)) {
            issues.add(ValidationIssue.error(IssueCode.ACTION_MISSING_NAME, index, "action", "ACTION intent missing action name"));
            return;
        }

        if (actionHandlerRegistry == null) {
            issues.add(ValidationIssue.warn(IssueCode.ACTION_REGISTRY_UNAVAILABLE, index, "action", "Action handler registry unavailable; skipping action validation"));
            return;
        }

        // Canonicalize action aliases ("relationship query" etc.) via registry metadata.
        AIActionMetaData meta = actionHandlerRegistry.findMetadata(actionName).orElse(null);
        String canonical = meta != null && StringUtils.hasText(meta.getName()) ? meta.getName() : actionName;

        if (actionHandlerRegistry.findHandler(canonical).isEmpty()) {
            // Unknown action names are unsafe: they will fail later in action execution.
            issues.add(ValidationIssue.error(IssueCode.ACTION_UNREGISTERED, index, "action", "No handler registered for action '" + canonical + "'"));
            return;
        }

        validateActionGroundingScope(intent, meta, index, issues);

        if ("relationship_query".equalsIgnoreCase(canonical)) {
            validateRelationshipQueryPostActionContract(intent, index, originalQuery, issues);
        }

        Set<String> required = meta != null ? meta.getRequiredParameters() : Collections.emptySet();
        if (required == null || required.isEmpty()) {
            return;
        }

        Map<String, Object> params = intent.getActionParams();
        for (String param : required) {
            if (!StringUtils.hasText(param)) {
                continue;
            }
            Object value = params != null ? params.get(param) : null;
            if (value == null || value.toString().isBlank()) {
                issues.add(ValidationIssue.warn(
                    IssueCode.ACTION_REQUIRED_PARAM_MISSING,
                    index,
                    "actionParams." + param,
                    "Missing required action parameter '" + param + "' for action '" + canonical + "'"
                ));
            }
        }
    }

    private void validateRelationshipQueryPostActionContract(Intent intent,
                                                            int index,
                                                            String originalQuery,
                                                            List<ValidationIssue> issues) {
        if (!StringUtils.hasText(originalQuery) || intent == null || issues == null) {
            return;
        }

        String stripped = RelationshipQueryHintPrefix.stripIfPresent(originalQuery);
        if (!StringUtils.hasText(stripped)) {
            return;
        }

        // Only attempt this check when the user explicitly used the relationship-query hint prefix.
        // This avoids punishing normal relationship_query extraction where the model may rewrite queries.
        String trimmedOriginal = originalQuery.trim();
        boolean hinted = trimmedOriginal.regionMatches(true, 0, "relationship_query:", 0, "relationship_query:".length())
            || trimmedOriginal.regionMatches(true, 0, "relationship query:", 0, "relationship query:".length())
            || trimmedOriginal.regionMatches(true, 0, "relationship-query:", 0, "relationship-query:".length());
        if (!hinted) {
            return;
        }

        Map<String, Object> params = intent.getActionParams();
        Object q = params != null ? params.get("query") : null;
        if (!(q instanceof String actionQuery) || !StringUtils.hasText(actionQuery)) {
            return;
        }

        boolean alreadyRequested = Boolean.TRUE.equals(intent.getRequiresGeneration()) || StringUtils.hasText(intent.getGenerationInstructions());
        if (alreadyRequested) {
            return;
        }

        String normalizedHinted = normalizeForPrefixCompare(stripped);
        String normalizedAction = normalizeForPrefixCompare(actionQuery);
        if (!StringUtils.hasText(normalizedHinted) || !StringUtils.hasText(normalizedAction)) {
            return;
        }

        // If the hinted query begins with the relational part but has a trailing remainder, the extractor likely
        // dropped a post-action instruction (e.g., "then summarize/explain/translate...").
        // Mark this as INCOMPLETE so the progressive completion step can ask the LLM to repair the contract.
        if (normalizedHinted.startsWith(normalizedAction) && normalizedHinted.length() > normalizedAction.length() + 5) {
            issues.add(ValidationIssue.error(
                IssueCode.RELATIONSHIP_QUERY_HINTED_TAIL_MISSING,
                index,
                "generationInstructions",
                "Hinted relationship_query message appears to include trailing instructions beyond actionParams.query. " +
                    "Ensure actionParams.query contains only the relational query and move any post-action request into generationInstructions (requiresGeneration=true)."
            ));
        }
    }

    private String normalizeForPrefixCompare(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        // Collapse whitespace so "Nike  and   then" doesn't defeat prefix detection.
        return trimmed.replaceAll("\\s+", " ");
    }

    private void validateInformationIntent(Intent intent, int index, List<ValidationIssue> issues) {
        if (intent.getNeedsAdvancedRAG() == null) {
            issues.add(ValidationIssue.error(
                IssueCode.INFORMATION_MISSING_ADVANCED_RAG_DECISION,
                index,
                "needsAdvancedRAG",
                "INFORMATION intent is missing needsAdvancedRAG semantic decision"
            ));
        }
        if (Boolean.FALSE.equals(intent.getRequiresRetrieval())) {
            if (!StringUtils.hasText(intent.getDirectAnswer())) {
                issues.add(ValidationIssue.warn(
                    IssueCode.INFORMATION_DIRECT_ANSWER_MISSING,
                    index,
                    "directAnswer",
                    "INFORMATION intent requiresRetrieval=false but directAnswer is missing"
                ));
            }
            return;
        }
        if (Boolean.TRUE.equals(intent.getRequiresRetrieval())
            && !StringUtils.hasText(intent.getVectorSpace())
            && !hasDeclaredClarification(intent)) {
            issues.add(ValidationIssue.error(
                IssueCode.INFORMATION_MISSING_VECTOR_SPACE,
                index,
                "vectorSpace",
                "INFORMATION intent requires retrieval but vectorSpace is missing"
            ));
        } else if (Boolean.TRUE.equals(intent.getRequiresRetrieval())
            && StringUtils.hasText(intent.getVectorSpace())) {
            validateRegisteredVectorSpaces(intent, index, issues);
        }
    }

    private boolean hasDeclaredClarification(Intent intent) {
        return intent != null
            && intent.getMissingInformation() != null
            && !intent.getMissingInformation().isEmpty()
            && StringUtils.hasText(intent.getClarificationQuestion());
    }

    private void validateActionGroundingScope(Intent intent,
                                              AIActionMetaData metadata,
                                              int index,
                                              List<ValidationIssue> issues) {
        if (intent == null
            || metadata == null
            || metadata.getGroundingVectorSpaces() == null
            || metadata.getGroundingVectorSpaces().isEmpty()
            || !StringUtils.hasText(intent.getVectorSpace())) {
            return;
        }
        Set<String> actionSpaces = normalizeVectorSpaces(metadata.getGroundingVectorSpaces());
        Set<String> selectedSpaces = normalizeVectorSpaces(List.of(intent.getVectorSpace().split(",")));
        if (!actionSpaces.isEmpty()
            && !selectedSpaces.isEmpty()
            && actionSpaces.stream().noneMatch(selectedSpaces::contains)) {
            issues.add(ValidationIssue.error(
                IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH,
                index,
                "vectorSpace",
                "Selected action cannot provide evidence for the selected vector space"
            ));
        }
    }

    private void validateRegisteredVectorSpaces(Intent intent,
                                                int index,
                                                List<ValidationIssue> issues) {
        KnowledgeBaseOverviewService service = knowledgeBaseOverviewServiceProvider != null
            ? knowledgeBaseOverviewServiceProvider.getIfAvailable()
            : null;
        KnowledgeBaseOverview overview = service != null ? service.getOverview() : null;
        if (overview == null || overview.getEntityTypes() == null || overview.getEntityTypes().isEmpty()) {
            return;
        }
        Set<String> registered = normalizeVectorSpaces(overview.getEntityTypes());
        Set<String> selected = normalizeVectorSpaces(List.of(intent.getVectorSpace().split(",")));
        List<String> invalid = selected.stream().filter(space -> !registered.contains(space)).toList();
        if (!invalid.isEmpty()) {
            issues.add(ValidationIssue.error(
                IssueCode.INFORMATION_INVALID_VECTOR_SPACE,
                index,
                "vectorSpace",
                "INFORMATION intent selected unregistered vector space(s): " + String.join(", ", invalid)
            ));
        }
    }

    private Set<String> normalizeVectorSpaces(java.util.Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public enum ErrorCategory {
        NONE,
        STRUCTURAL,
        UNSAFE,
        INCOMPLETE,
        OTHER
    }

    public enum Severity {
        ERROR,
        WARNING
    }

    public enum IssueCode {
        RESPONSE_NULL,
        RESPONSE_EMPTY_INTENTS,
        INTENT_NULL,
        INTENT_MISSING_TYPE,
        INTENT_MISSING_NAME,
        INTENT_MISSING_RETRIEVAL_DECISION,
        INTENT_MISSING_GENERATION_DECISION,
        INTENT_MISSING_TARGET_DECISION,
        INTENT_MISSING_OBLIGATION_ID,
        INTENT_MISSING_CLARIFICATION_QUESTION,
        ACTION_MISSING_NAME,
        ACTION_REGISTRY_UNAVAILABLE,
        ACTION_UNREGISTERED,
        ACTION_REQUIRED_PARAM_MISSING,
        ACTION_GROUNDING_VECTOR_SPACE_MISMATCH,
        SEMANTIC_RECOVERY_NOT_ALLOWED,
        RELATIONSHIP_QUERY_HINTED_TAIL_MISSING,
        INFORMATION_DIRECT_ANSWER_MISSING,
        INFORMATION_MISSING_VECTOR_SPACE,
        INFORMATION_INVALID_VECTOR_SPACE,
        INFORMATION_MISSING_ADVANCED_RAG_DECISION,
        OUT_OF_SCOPE_RESPONSE_MISSING,
        OUT_OF_SCOPE_EXECUTION_NOT_ALLOWED
    }

    public record ValidationIssue(
        IssueCode code,
        Severity severity,
        int intentIndex,
        String field,
        String message
    ) {
        static ValidationIssue error(IssueCode code, int index, String field, String message) {
            return new ValidationIssue(code, Severity.ERROR, index, field, message);
        }

        static ValidationIssue warn(IssueCode code, int index, String field, String message) {
            return new ValidationIssue(code, Severity.WARNING, index, field, message);
        }
    }

    public record ValidationResult(
        boolean valid,
        ErrorCategory errorCategory,
        List<ValidationIssue> issues,
        List<String> errors,
        List<String> warnings
    ) {
        public ValidationResult(boolean valid, ErrorCategory errorCategory, List<String> errors, List<String> warnings) {
            this(valid, errorCategory, List.of(), errors, warnings);
        }

        public boolean isStructuralFailure() {
            return !valid && errorCategory == ErrorCategory.STRUCTURAL;
        }
    }

    private ValidationResult toResult(List<ValidationIssue> issues) {
        List<ValidationIssue> safeIssues = issues == null ? List.of() : List.copyOf(issues);
        List<String> errors = safeIssues.stream()
            .filter(issue -> issue != null && issue.severity() == Severity.ERROR)
            .map(ValidationIssue::message)
            .filter(Objects::nonNull)
            .toList();
        List<String> warnings = safeIssues.stream()
            .filter(issue -> issue != null && issue.severity() == Severity.WARNING)
            .map(ValidationIssue::message)
            .filter(Objects::nonNull)
            .toList();

        ErrorCategory category = categorizeIssues(safeIssues);
        boolean valid = errors.isEmpty() && category != ErrorCategory.UNSAFE && category != ErrorCategory.INCOMPLETE;
        return new ValidationResult(valid, category, safeIssues, errors, warnings);
    }

    private ErrorCategory categorizeIssues(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return ErrorCategory.NONE;
        }

        boolean hasErrors = issues.stream().anyMatch(issue -> issue != null && issue.severity() == Severity.ERROR);
        if (!hasErrors) {
            return ErrorCategory.NONE;
        }

        if (hasIssue(issues, Set.of(
            IssueCode.RESPONSE_NULL,
            IssueCode.INTENT_NULL,
            IssueCode.INTENT_MISSING_TYPE,
            IssueCode.INTENT_MISSING_NAME,
            IssueCode.ACTION_MISSING_NAME
        ))) {
            return ErrorCategory.STRUCTURAL;
        }
        if (hasIssue(issues, Set.of(
            IssueCode.ACTION_UNREGISTERED,
            IssueCode.SEMANTIC_RECOVERY_NOT_ALLOWED
        ))) {
            return ErrorCategory.UNSAFE;
        }
        if (hasIssue(issues, Set.of(
            IssueCode.ACTION_REQUIRED_PARAM_MISSING,
            IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH,
            IssueCode.RELATIONSHIP_QUERY_HINTED_TAIL_MISSING,
            IssueCode.INFORMATION_MISSING_VECTOR_SPACE,
            IssueCode.INFORMATION_INVALID_VECTOR_SPACE,
            IssueCode.INFORMATION_MISSING_ADVANCED_RAG_DECISION,
            IssueCode.OUT_OF_SCOPE_RESPONSE_MISSING,
            IssueCode.OUT_OF_SCOPE_EXECUTION_NOT_ALLOWED,
            IssueCode.INTENT_MISSING_RETRIEVAL_DECISION,
            IssueCode.INTENT_MISSING_GENERATION_DECISION,
            IssueCode.INTENT_MISSING_TARGET_DECISION,
            IssueCode.INTENT_MISSING_OBLIGATION_ID,
            IssueCode.INTENT_MISSING_CLARIFICATION_QUESTION
        ))) {
            return ErrorCategory.INCOMPLETE;
        }
        return ErrorCategory.OTHER;
    }

    private boolean hasIssue(List<ValidationIssue> issues, Set<IssueCode> codes) {
        return issues.stream().anyMatch(issue ->
            issue != null
                && issue.severity() == Severity.ERROR
                && issue.code() != null
                && codes.contains(issue.code())
        );
    }
}
