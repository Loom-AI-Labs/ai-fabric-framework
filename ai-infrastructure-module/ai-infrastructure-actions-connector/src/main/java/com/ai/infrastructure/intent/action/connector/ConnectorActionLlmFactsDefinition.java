package com.ai.infrastructure.intent.action.connector;

import java.util.List;

/**
 * Generic post-action fact projection rules for connector-backed actions.
 *
 * <p>The connector framework owns the projection mechanics only. Domain field
 * names, typed parameter paths, and output labels belong in the action catalog.</p>
 */
public record ConnectorActionLlmFactsDefinition(
    String rootPath,
    List<String> copyFields,
    List<ConnectorActionLlmFactsListDefinition> lists,
    List<ConnectorActionLlmFactsObjectDefinition> objects
) {
    public ConnectorActionLlmFactsDefinition {
        copyFields = copyFields != null ? List.copyOf(copyFields) : List.of();
        lists = lists != null ? List.copyOf(lists) : List.of();
        objects = objects != null ? List.copyOf(objects) : List.of();
    }

    boolean configured() {
        return !copyFields.isEmpty() || !lists.isEmpty() || !objects.isEmpty();
    }
}

record ConnectorActionLlmFactsListDefinition(
    String sourcePath,
    String target,
    int maxItems,
    List<String> includeFields,
    String fallbackContentField,
    int fallbackContentMaxChars,
    List<ConnectorActionLlmFactsRuleDefinition> rankRules,
    ConnectorActionLlmFactsConstraintDefinition constraints,
    List<ConnectorActionLlmFactsSummaryDefinition> summaries
) {
    ConnectorActionLlmFactsListDefinition {
        maxItems = maxItems > 0 ? maxItems : 5;
        includeFields = includeFields != null ? List.copyOf(includeFields) : List.of();
        fallbackContentMaxChars = fallbackContentMaxChars > 0 ? fallbackContentMaxChars : 300;
        rankRules = rankRules != null ? List.copyOf(rankRules) : List.of();
        summaries = summaries != null ? List.copyOf(summaries) : List.of();
    }
}

record ConnectorActionLlmFactsObjectDefinition(
    String sourcePath,
    String target,
    List<String> includeFields,
    String fallbackContentField,
    int fallbackContentMaxChars
) {
    ConnectorActionLlmFactsObjectDefinition {
        includeFields = includeFields != null ? List.copyOf(includeFields) : List.of();
        fallbackContentMaxChars = fallbackContentMaxChars > 0 ? fallbackContentMaxChars : 300;
    }
}

record ConnectorActionLlmFactsRuleDefinition(
    String type,
    String field,
    String paramPath,
    String operator,
    Object value,
    int score,
    int scoreMatch,
    int scoreMissing,
    int scoreMismatch,
    boolean sortAscendingOnMatch
) {
}

record ConnectorActionLlmFactsConstraintDefinition(
    String target,
    String countTarget,
    List<String> includeFields,
    List<ConnectorActionLlmFactsRuleDefinition> rules
) {
    ConnectorActionLlmFactsConstraintDefinition {
        includeFields = includeFields != null ? List.copyOf(includeFields) : List.of();
        rules = rules != null ? List.copyOf(rules) : List.of();
    }
}

record ConnectorActionLlmFactsSummaryDefinition(
    String target,
    String source,
    String field,
    String recordCountKey,
    String lowestValueKey,
    String highestValueKey,
    String labelField,
    String lowestLabelKey,
    String highestLabelKey,
    List<ConnectorActionLlmFactsSummaryExtraFieldDefinition> extraFields
) {
    ConnectorActionLlmFactsSummaryDefinition {
        extraFields = extraFields != null ? List.copyOf(extraFields) : List.of();
    }
}

record ConnectorActionLlmFactsSummaryExtraFieldDefinition(
    String field,
    String lowestKey,
    String highestKey
) {
}
