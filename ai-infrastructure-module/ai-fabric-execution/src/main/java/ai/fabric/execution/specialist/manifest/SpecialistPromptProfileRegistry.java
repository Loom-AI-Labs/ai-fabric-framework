package ai.fabric.execution.specialist.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SpecialistPromptProfileRegistry {

    private static final int MAX_CONSTRAINT_CHARACTERS = 8_000;
    private static final int MAX_OUTPUT_CONTRACT_CHARACTERS = 8_000;

    private final Map<SpecialistPromptProfileId, SpecialistPromptProfile> profiles;

    public SpecialistPromptProfileRegistry(
        List<SpecialistPromptProfile> definitions
    ) {
        Map<SpecialistPromptProfileId, SpecialistPromptProfile> loaded =
            new LinkedHashMap<>();
        for (SpecialistPromptProfile profile :
            definitions == null
                ? List.<SpecialistPromptProfile>of()
                : definitions) {
            validate(profile);
            if (loaded.putIfAbsent(profile.id(), profile) != null) {
                throw new SpecialistManifestException(
                    "DUPLICATE_PROMPT_PROFILE_ID",
                    "Duplicate specialist prompt profile " + profile.id() + ".",
                    "prompt-profile:" + profile.id()
                );
            }
        }
        this.profiles = Map.copyOf(loaded);
    }

    public Optional<SpecialistPromptProfile> find(
        SpecialistPromptProfileId id
    ) {
        return Optional.ofNullable(profiles.get(id));
    }

    public SpecialistPromptProfile require(SpecialistPromptProfileId id) {
        return find(id).orElseThrow(() -> new SpecialistManifestException(
            "PROMPT_PROFILE_REFERENCE_NOT_FOUND",
            "No specialist prompt profile is registered for " + id + ".",
            "prompt-profile:" + id
        ));
    }

    public List<SpecialistPromptProfile> list() {
        return List.copyOf(profiles.values());
    }

    private void validate(SpecialistPromptProfile profile) {
        Objects.requireNonNull(profile, "prompt profile must not be null");
        if (!"ai.fabric/v1".equals(profile.apiVersion())) {
            throw new SpecialistManifestException(
                "RESOURCE_API_VERSION_UNSUPPORTED",
                "Only ai.fabric/v1 specialist resources are supported.",
                "prompt-profile"
            );
        }
        if (!"SpecialistPromptProfile".equals(profile.kind())) {
            throw new SpecialistManifestException(
                "RESOURCE_KIND_INVALID",
                "Prompt resources must use kind SpecialistPromptProfile.",
                "prompt-profile"
            );
        }
        if (profile.metadata() == null || profile.spec() == null) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_INCOMPLETE",
                "Prompt profile metadata and spec are required.",
                "prompt-profile"
            );
        }
        bounded(
            profile.spec().constraints(),
            "constraints",
            MAX_CONSTRAINT_CHARACTERS,
            profile.id()
        );
        bounded(
            profile.spec().outputContract(),
            "outputContract",
            MAX_OUTPUT_CONTRACT_CHARACTERS,
            profile.id()
        );
    }

    private void bounded(
        String value,
        String field,
        int maxCharacters,
        SpecialistPromptProfileId id
    ) {
        if (value == null || value.isBlank()) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_FIELD_REQUIRED",
                "Prompt profile " + field + " is required.",
                "prompt-profile:" + id
            );
        }
        if (value.trim().length() > maxCharacters) {
            throw new SpecialistManifestException(
                "PROMPT_PROFILE_FIELD_TOO_LARGE",
                "Prompt profile " + field + " exceeds its size limit.",
                "prompt-profile:" + id
            );
        }
    }
}
