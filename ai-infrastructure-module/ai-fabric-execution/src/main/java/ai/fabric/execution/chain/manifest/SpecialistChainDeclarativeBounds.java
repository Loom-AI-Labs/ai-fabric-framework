package ai.fabric.execution.chain.manifest;

/** Deployment ceilings for declarative JSON selection and projection work. */
public record SpecialistChainDeclarativeBounds(
    int maxPointerCharacters,
    int maxPointerDepth,
    int maxManagerContextEntries,
    int maxMappingsPerTarget,
    int maxMappingsPerChain,
    int maxNodeDepth,
    int maxNodeCount,
    int maxCopiedValueBytes,
    int maxMappedObjectBytes,
    int maxTotalMappingWork
) {
    public SpecialistChainDeclarativeBounds {
        requirePositive(maxPointerCharacters, "maxPointerCharacters");
        requirePositive(maxPointerDepth, "maxPointerDepth");
        requirePositive(
            maxManagerContextEntries,
            "maxManagerContextEntries"
        );
        requirePositive(maxMappingsPerTarget, "maxMappingsPerTarget");
        requirePositive(maxMappingsPerChain, "maxMappingsPerChain");
        requirePositive(maxNodeDepth, "maxNodeDepth");
        requirePositive(maxNodeCount, "maxNodeCount");
        requirePositive(maxCopiedValueBytes, "maxCopiedValueBytes");
        requirePositive(maxMappedObjectBytes, "maxMappedObjectBytes");
        requirePositive(maxTotalMappingWork, "maxTotalMappingWork");
    }

    public int maxJsonPointerCharacters() {
        return maxPointerCharacters;
    }

    public int maxJsonPointerDepth() {
        return maxPointerDepth;
    }

    public int maxCopiedNodeDepth() {
        return maxNodeDepth;
    }

    public int maxCopiedNodeCount() {
        return maxNodeCount;
    }

    public int maxMappedInputBytes() {
        return maxMappedObjectBytes;
    }

    public int maxMappingWorkUnits() {
        return maxTotalMappingWork;
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
