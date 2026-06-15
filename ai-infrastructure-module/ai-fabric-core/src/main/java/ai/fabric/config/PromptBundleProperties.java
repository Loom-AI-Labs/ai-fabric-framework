package ai.fabric.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Global prompt bundle resolution configuration.
 *
 * <p>The framework resolves prompt templates by trying overlay versions first, then the base version.</p>
 *
 * <p>This enables "default bundle + delta overrides" without requiring curated packs/provider bundles to
 * duplicate every template.</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "ai.prompts.bundle")
public class PromptBundleProperties {

    /**
     * Base (complete) prompt bundle version.
     */
    @NotBlank
    private String baseVersion = "v1";

    /**
     * Overlay versions to try before {@link #baseVersion}.
     *
     * <p>Example: ["v1-domain", "v1-provider"]</p>
     */
    private List<String> overlays = new ArrayList<>();

    public List<String> candidateVersions() {
        Set<String> unique = new LinkedHashSet<>();
        if (overlays != null) {
            for (String overlay : overlays) {
                if (overlay == null) {
                    continue;
                }
                String trimmed = overlay.trim();
                if (!trimmed.isEmpty()) {
                    unique.add(trimmed);
                }
            }
        }

        String base = baseVersion != null ? baseVersion.trim() : null;
        if (base != null && !base.isEmpty()) {
            unique.add(base);
        }

        return List.copyOf(unique);
    }
}
