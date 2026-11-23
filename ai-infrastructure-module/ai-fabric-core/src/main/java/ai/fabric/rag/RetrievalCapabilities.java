package ai.fabric.rag;

/** Readiness of the resolved corpus, not merely the provider's advertised features. */
public record RetrievalCapabilities(boolean denseReady, boolean lexicalReady,
                                    String lexicalAlgorithm, String indexGeneration, String reason) {
    public static RetrievalCapabilities denseOnly(String reason) {
        return new RetrievalCapabilities(true, false, "", "", reason);
    }
}
