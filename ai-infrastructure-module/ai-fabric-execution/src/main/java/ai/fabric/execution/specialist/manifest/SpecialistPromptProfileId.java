package ai.fabric.execution.specialist.manifest;

import java.util.Objects;
import java.util.regex.Pattern;

public record SpecialistPromptProfileId(String name, String version) {

    private static final Pattern NAME = Pattern.compile(
        "[a-z][a-z0-9-]{0,79}"
    );
    private static final Pattern VERSION = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
    );

    public SpecialistPromptProfileId {
        name = requireText(name, "name");
        version = requireText(version, "version");
        if (!NAME.matcher(name).matches()) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_ID_INVALID",
                "Prompt profile names must use lowercase letters, digits, and hyphens.",
                "prompt-profile"
            );
        }
        if (!VERSION.matcher(version).matches()) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_ID_INVALID",
                "Prompt profile versions contain unsupported characters.",
                "prompt-profile:" + name
            );
        }
    }

    public static SpecialistPromptProfileId parse(String value) {
        String normalized = requireText(value, "promptProfileRef");
        int separator = normalized.lastIndexOf('@');
        if (separator < 1 || separator == normalized.length() - 1) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_REFERENCE_INVALID",
                "promptProfileRef must use the exact name@version form.",
                "prompt-profile-reference"
            );
        }
        return new SpecialistPromptProfileId(
            normalized.substring(0, separator),
            normalized.substring(separator + 1)
        );
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
