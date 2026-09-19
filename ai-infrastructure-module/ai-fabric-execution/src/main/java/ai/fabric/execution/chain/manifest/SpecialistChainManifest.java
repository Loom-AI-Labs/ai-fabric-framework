package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainLimits;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict ai.fabric/v1 declarative specialist-chain resource. */
public record SpecialistChainManifest(
    String apiVersion,
    String kind,
    Metadata metadata,
    Spec spec
) {
    public record Metadata(
        String name,
        String version,
        String displayName,
        String description,
        Map<String, String> labels
    ) {
        public Metadata {
            labels = labels == null || labels.isEmpty()
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(labels));
        }
    }

    public record Spec(
        Input input,
        Manager manager,
        List<Target> targets,
        SpecialistChainLimits limits,
        SpecialistChainConversationPolicy conversationPolicy
    ) {
        public Spec {
            targets = targets == null ? List.of() : List.copyOf(targets);
        }
    }

    public record Input(
        String schemaRef,
        String managerMessagePointer,
        List<ContextValue> managerContext
    ) {
        public Input {
            managerContext = managerContext == null
                ? List.of()
                : List.copyOf(managerContext);
        }
    }

    public record ContextValue(
        String name,
        String valuePointer,
        Boolean required
    ) {
        public boolean requiredOrDefault() {
            return required == null || required;
        }
    }

    public record Manager(String specialistRef) {}

    public record Target(
        String specialistRef,
        String description,
        TargetInput input,
        TargetResult result,
        Transitions transitions
    ) {}

    public record TargetInput(
        SpecialistChainInputMappingType type,
        List<MappingField> fields
    ) {
        public TargetInput {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    public record MappingField(
        SpecialistChainMappingSource source,
        String sourcePointer,
        String targetField,
        Boolean required
    ) {
        public boolean requiredOrDefault() {
            return required == null || required;
        }
    }

    public record TargetResult(
        SpecialistChainResultProjectionType type,
        String summaryPointer,
        List<FactField> facts,
        SpecialistChainEvidencePolicy evidenceReferences
    ) {
        public TargetResult {
            facts = facts == null ? List.of() : List.copyOf(facts);
        }
    }

    public record FactField(
        String name,
        String valuePointer,
        Boolean required
    ) {
        public boolean requiredOrDefault() {
            return required == null || required;
        }
    }

    public record Transitions(
        Boolean delegationAllowed,
        Boolean parallelEligible,
        Boolean handoffAllowed
    ) {}
}
