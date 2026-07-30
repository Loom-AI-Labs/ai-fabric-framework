package ai.fabric.intent.retrieval.connector;

import java.net.IDN;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, validated external retrieval response policy.
 */
public record RetrievalResponsePolicy(
    int maxDocuments,
    int maxResponseCharacters,
    int maxDocumentIdCharacters,
    int maxContentCharacters,
    int maxContextCharacters,
    int maxSourceCharacters,
    int maxUrlCharacters,
    int maxVectorSpaceCharacters,
    int maxMetadataEntries,
    int maxMetadataDepth,
    int maxMetadataCharacters,
    int maxMessageCharacters,
    int maxErrorCodeCharacters,
    Set<String> allowedUrlSchemes,
    Set<String> allowedUrlHostSuffixes,
    Set<String> allowedMetadataKeys,
    RetrievalUnknownMetadataPolicy unknownMetadataPolicy
) {
    private static final Pattern URL_SCHEME =
        Pattern.compile("[a-z][a-z0-9+.-]*");
    private static final int MAX_METADATA_KEY_CHARACTERS = 128;

    public RetrievalResponsePolicy {
        requirePositive(maxDocuments, "maxDocuments");
        requirePositive(maxResponseCharacters, "maxResponseCharacters");
        requirePositive(
            maxDocumentIdCharacters,
            "maxDocumentIdCharacters"
        );
        requirePositive(maxContentCharacters, "maxContentCharacters");
        requirePositive(maxContextCharacters, "maxContextCharacters");
        requirePositive(maxSourceCharacters, "maxSourceCharacters");
        requirePositive(maxUrlCharacters, "maxUrlCharacters");
        requirePositive(
            maxVectorSpaceCharacters,
            "maxVectorSpaceCharacters"
        );
        requirePositive(maxMetadataEntries, "maxMetadataEntries");
        requirePositive(maxMetadataDepth, "maxMetadataDepth");
        requirePositive(maxMetadataCharacters, "maxMetadataCharacters");
        requirePositive(maxMessageCharacters, "maxMessageCharacters");
        requirePositive(
            maxErrorCodeCharacters,
            "maxErrorCodeCharacters"
        );
        if (maxDocuments > 10_000) {
            throw new IllegalArgumentException(
                "maxDocuments must not exceed 10000"
            );
        }
        allowedUrlSchemes = normalizeSchemes(allowedUrlSchemes);
        allowedUrlHostSuffixes = normalizeHostSuffixes(
            allowedUrlHostSuffixes
        );
        allowedMetadataKeys = normalizeMetadataKeys(allowedMetadataKeys);
        unknownMetadataPolicy = Objects.requireNonNullElse(
            unknownMetadataPolicy,
            RetrievalUnknownMetadataPolicy.DROP
        );
    }

    public static RetrievalResponsePolicy from(
        AIRetrievalConnectorProperties properties
    ) {
        Objects.requireNonNull(properties, "properties is required");
        AIRetrievalConnectorProperties.ResponsePolicyProperties source =
            Objects.requireNonNullElseGet(
                properties.getResponsePolicy(),
                AIRetrievalConnectorProperties.ResponsePolicyProperties::new
            );
        return new RetrievalResponsePolicy(
            source.getMaxDocuments(),
            source.getMaxResponseCharacters(),
            source.getMaxDocumentIdCharacters(),
            source.getMaxContentCharacters(),
            source.getMaxContextCharacters(),
            source.getMaxSourceCharacters(),
            source.getMaxUrlCharacters(),
            source.getMaxVectorSpaceCharacters(),
            source.getMaxMetadataEntries(),
            source.getMaxMetadataDepth(),
            source.getMaxMetadataCharacters(),
            source.getMaxMessageCharacters(),
            source.getMaxErrorCodeCharacters(),
            source.getAllowedUrlSchemes(),
            source.getAllowedUrlHostSuffixes(),
            source.getAllowedMetadataKeys(),
            source.getUnknownMetadataPolicy()
        );
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(
                field + " must be positive"
            );
        }
    }

    private static Set<String> normalizeSchemes(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String scheme = value.trim().toLowerCase(Locale.ROOT);
                if (!URL_SCHEME.matcher(scheme).matches()) {
                    throw new IllegalArgumentException(
                        "allowedUrlSchemes contains an invalid scheme"
                    );
                }
                normalized.add(scheme);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> normalizeHostSuffixes(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String suffix = value.trim().toLowerCase(Locale.ROOT);
                while (suffix.startsWith(".")) {
                    suffix = suffix.substring(1);
                }
                while (suffix.endsWith(".")) {
                    suffix = suffix.substring(0, suffix.length() - 1);
                }
                if (suffix.isBlank() || containsControlCharacter(suffix)) {
                    throw new IllegalArgumentException(
                        "allowedUrlHostSuffixes contains an invalid host suffix"
                    );
                }
                try {
                    normalized.add(
                        IDN.toASCII(suffix, IDN.USE_STD3_ASCII_RULES)
                            .toLowerCase(Locale.ROOT)
                    );
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                        "allowedUrlHostSuffixes contains an invalid host suffix",
                        ex
                    );
                }
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static Set<String> normalizeMetadataKeys(Set<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                String key = value.trim();
                if (key.length() > MAX_METADATA_KEY_CHARACTERS
                    || key.startsWith(".")
                    || key.endsWith(".")
                    || key.contains("..")
                    || containsControlCharacter(key)
                    || reservedMetadataPath(key)) {
                    throw new IllegalArgumentException(
                        "allowedMetadataKeys contains an invalid path"
                    );
                }
                normalized.add(key);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    static boolean reservedMetadataPath(String path) {
        if (path == null) {
            return false;
        }
        for (String segment : path.split("\\.")) {
            if (segment.toLowerCase(Locale.ROOT).startsWith("_aifabric")) {
                return true;
            }
        }
        return false;
    }

    static boolean containsControlCharacter(String value) {
        return value != null && value.chars().anyMatch(
            character -> Character.isISOControl(character)
        );
    }
}
