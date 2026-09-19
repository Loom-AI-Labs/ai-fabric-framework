package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.specialist.manifest.SpecialistCompilationDiagnostic;
import java.util.List;

/** Safe aggregate status for declarative chain loading and compilation. */
public record SpecialistChainManifestRuntimeStatus(
    boolean manifestLoadingEnabled,
    boolean chainExecutionEnabled,
    boolean ready,
    int javaDefinedCount,
    int discoveredManifestCount,
    int inactiveManifestCount,
    int manifestDefinedCount,
    int totalRegisteredCount,
    String auditResourceAggregateHash,
    String declarativeSemanticsAggregateHash,
    String effectiveExecutionAggregateHash,
    List<SpecialistCompilationDiagnostic> diagnostics
) {
    public SpecialistChainManifestRuntimeStatus {
        requireNonNegative(javaDefinedCount, "javaDefinedCount");
        requireNonNegative(
            discoveredManifestCount,
            "discoveredManifestCount"
        );
        requireNonNegative(inactiveManifestCount, "inactiveManifestCount");
        requireNonNegative(manifestDefinedCount, "manifestDefinedCount");
        requireNonNegative(totalRegisteredCount, "totalRegisteredCount");
        auditResourceAggregateHash = normalizeHash(auditResourceAggregateHash);
        declarativeSemanticsAggregateHash = normalizeHash(
            declarativeSemanticsAggregateHash
        );
        effectiveExecutionAggregateHash = normalizeHash(
            effectiveExecutionAggregateHash
        );
        diagnostics = diagnostics == null
            ? List.of()
            : List.copyOf(diagnostics);
    }

    public static SpecialistChainManifestRuntimeStatus empty(
        boolean manifestsEnabled,
        boolean chainsEnabled
    ) {
        return new SpecialistChainManifestRuntimeStatus(
            manifestsEnabled,
            chainsEnabled,
            true,
            0,
            0,
            0,
            0,
            0,
            "",
            "",
            "",
            List.of()
        );
    }

    private static String normalizeHash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                "Aggregate identities must be lowercase SHA-256 values"
            );
        }
        return normalized;
    }

    private static void requireNonNegative(int value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }
}
