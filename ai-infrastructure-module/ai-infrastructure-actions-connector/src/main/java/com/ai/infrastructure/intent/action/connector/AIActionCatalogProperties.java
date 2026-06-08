package com.ai.infrastructure.intent.action.connector;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for loading non-annotation action contracts (e.g., file-based connector actions).
 *
 * <p>Greenfield: action contracts should be validated at startup. If a source is configured but invalid,
 * the application should fail fast rather than running with a partial/ambiguous catalog.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.actions")
public class AIActionCatalogProperties {

    /**
     * Action catalog sources to load at startup.
     *
     * <p>Example:</p>
     * <pre>{@code
     * ai:
     *   actions:
     *     sources:
     *       - type: file
     *         path: classpath:ai-actions.yml
     * }</pre>
     */
    private List<ActionSourceProperties> sources = new ArrayList<>();

    /**
     * Action catalog source configuration.
     */
    @Data
    public static class ActionSourceProperties {
        /**
         * Source type. Only {@link ActionSourceType#FILE} is supported in the initial release.
         */
        private ActionSourceType type = ActionSourceType.FILE;

        /**
         * Source path (Spring resource syntax supported), e.g. {@code classpath:ai-actions.yml} or {@code file:/...}.
         */
        private String path;

        /**
         * When {@code true}, a missing source file is ignored (useful for "mount if present" deployment patterns).
         *
         * <p>Note: if the file exists but is invalid/unreadable, startup will still fail fast.</p>
         */
        private boolean optional = false;
    }

    /**
     * Supported action catalog source types.
     */
    public enum ActionSourceType {
        FILE,
        DB
    }
}
