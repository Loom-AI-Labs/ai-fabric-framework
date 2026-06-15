package ai.fabric.intent.action;

import java.util.Locale;

/**
 * Normalization utilities for action names.
 *
 * <p>Greenfield: normalization is used for registry lookup and duplicate detection. It must remain stable
 * across action sources (annotations, file-based catalogs, DB-backed catalogs).</p>
 */
public final class AIActionNames {

    private AIActionNames() {
    }

    /**
     * Normalize an action name into a stable registry key.
     *
     * <p>Normalization strategy:</p>
     * <ul>
     *   <li>trim + lower-case</li>
     *   <li>replace non {@code [a-z0-9_]} with underscores</li>
     *   <li>collapse duplicate underscores</li>
     *   <li>strip leading/trailing underscores</li>
     *   <li>strip trailing {@code _action} suffix</li>
     * </ul>
     *
     * @param value action name (required)
     * @return normalized key
     */
    public static String normalize(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value is required");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9_]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.endsWith("_action")) {
            normalized = normalized.substring(0, normalized.length() - "_action".length());
        }
        return normalized;
    }
}

