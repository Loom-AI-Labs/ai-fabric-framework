package ai.fabric.cache;

/**
 * Shared cache names used by AI Fabric services and auto-configuration.
 */
public final class AICacheNames {

    public static final String EMBEDDINGS = "ai-fabric-embeddings";
    public static final String VECTOR_SEARCH = "ai-fabric-vector-search";
    public static final String TEXT_SEARCH = "ai-fabric-text-search";
    public static final String AI_GENERATION = "ai-fabric-generation";
    public static final String AI_VALIDATION = "ai-fabric-validation";
    public static final String RETENTION_STATUS = "ai-fabric-retention-status";
    public static final String BEHAVIOR_RETENTION = "ai-fabric-behavior-retention";
    public static final String ACCESS_DECISIONS = "ai-fabric-access-decisions";

    private AICacheNames() {
    }
}
