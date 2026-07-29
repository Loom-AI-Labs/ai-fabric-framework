package ai.fabric.execution.specialist;

import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.util.TreeSet;

final class SpecialistDefinitionFingerprinter {

    private SpecialistDefinitionFingerprinter() {}

    static String fingerprint(SpecialistDefinition<?, ?> definition) {
        var profile = definition.executionProfile();
        var capabilities = profile.requestedCapabilities();
        String declaration = String.join(
            "\n",
            definition.id().toString(),
            definition.identity().displayName(),
            definition.identity().description(),
            definition.instructions().render(),
            profile.mode(),
            profile.strategy().name(),
            profile.writePolicy().name(),
            Boolean.toString(capabilities.retrievalEnabled()),
            new TreeSet<>(capabilities.requestedVectorSpaces()).toString(),
            new TreeSet<>(capabilities.visibleActions()).toString(),
            new TreeSet<>(capabilities.requestableReadActions()).toString(),
            new TreeSet<>(capabilities.proposableWriteActions()).toString(),
            definition.limits().toString(),
            new TreeSet<>(
                definition.delegationPolicy().allowedTargets().stream()
                    .map(SpecialistId::toString)
                    .toList()
            ).toString(),
            definition.inputAdapter().getClass().getName(),
            definition.inputAdapter().inputType().getName(),
            definition.inputAdapter().inputContinuation()
                .map(ai.fabric.execution.input.SpecialistInputContinuation::id)
                .orElse("none"),
            definition.outputAdapter().getClass().getName(),
            definition.outputAdapter().outputType().getName(),
            definition.outputAdapter().outputMode().name()
        );
        return CanonicalJsonSupport.sha256(declaration);
    }
}
