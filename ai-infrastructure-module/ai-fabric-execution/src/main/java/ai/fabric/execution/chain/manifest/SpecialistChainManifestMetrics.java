package ai.fabric.execution.chain.manifest;

/** Low-cardinality startup metrics for declarative specialist chains. */
public interface SpecialistChainManifestMetrics {

    void recordLoad(String result, String reason);

    void recordCompilation(String result, String reason);

    void recordMapping(String result, String reason);

    void recordProjection(String result, String reason);

    void recordRegistryCounts(
        int javaDefinitions,
        int manifestDefinitions,
        int inactiveManifests
    );

    static SpecialistChainManifestMetrics noop() {
        return new SpecialistChainManifestMetrics() {
            @Override
            public void recordLoad(String result, String reason) {}

            @Override
            public void recordCompilation(String result, String reason) {}

            @Override
            public void recordMapping(String result, String reason) {}

            @Override
            public void recordProjection(String result, String reason) {}

            @Override
            public void recordRegistryCounts(
                int javaDefinitions,
                int manifestDefinitions,
                int inactiveManifests
            ) {}
        };
    }
}
