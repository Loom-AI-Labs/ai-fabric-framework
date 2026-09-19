package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.DefaultSpecialistChainRegistry;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.chain.SpecialistChainRegistration;
import ai.fabric.execution.chain.SpecialistChainRegistrationBundle;
import ai.fabric.execution.chain.SpecialistChainRegistrationIdentity;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistOutputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistManifestException;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** V1 compiler for bounded, expression-free declarative chains. */
public final class DefaultSpecialistChainManifestCompiler
    implements SpecialistChainManifestCompiler {

    private static final Pattern NAME = Pattern.compile(
        "[a-z][a-z0-9-]{0,79}"
    );
    private static final Pattern VERSION = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
    );
    private static final Pattern LABEL_NAME = Pattern.compile(
        "[a-z][a-z0-9.-]{0,62}"
    );
    private final SpecialistChainManifestMetrics metrics;

    public DefaultSpecialistChainManifestCompiler() {
        this(SpecialistChainManifestMetrics.noop());
    }

    public DefaultSpecialistChainManifestCompiler(
        SpecialistChainManifestMetrics metrics
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics are required");
    }

    @Override
    public SpecialistChainRegistration compile(
        LoadedSpecialistChainManifest loaded,
        SpecialistChainCompilationContext context
    ) {
        Objects.requireNonNull(loaded, "loaded is required");
        Objects.requireNonNull(context, "context is required");
        SpecialistChainManifest manifest = loaded.manifest();
        try {
            validateEnvelope(manifest, loaded.source());
            validateMetadata(manifest.metadata(), loaded.source());
            SpecialistChainId chainId = chainId(manifest, loaded.source());
            SpecialistChainManifest.Spec spec = Objects.requireNonNull(
                manifest.spec(),
                "spec is required"
            );
            validateStructure(spec, context.bounds(), loaded.source());

            SpecialistSchemaId inputSchemaId = SpecialistSchemaId.parse(
                spec.input().schemaRef()
            );
            SpecialistSchemaDefinition inputSchema;
            try {
                inputSchema = context.schemaRegistry().require(
                    inputSchemaId,
                    SpecialistSchemaDirection.INPUT
                );
            } catch (RuntimeException ex) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_SCHEMA_NOT_FOUND",
                    "The chain input schema reference is not available.",
                    loaded.source(),
                    ex
                );
            }
            Map<SpecialistSchemaId, String> schemaDependencies =
                new LinkedHashMap<>();
            addSchemaDependency(
                schemaDependencies,
                inputSchema,
                context
            );

            SpecialistId managerId = parseSpecialist(
                spec.manager().specialistRef(),
                "CHAIN_MANIFEST_MANAGER_NOT_FOUND",
                loaded.source()
            );
            requireSpecialist(
                context,
                managerId,
                "CHAIN_MANIFEST_MANAGER_NOT_FOUND",
                loaded.source()
            );
            if (spec.targets().stream().anyMatch(target ->
                managerId.equals(parseSpecialist(
                    target.specialistRef(),
                    "CHAIN_MANIFEST_TARGET_NOT_FOUND",
                    loaded.source()
                ))
            )) {
                throw failure(
                    "CHAIN_MANIFEST_TARGET_BINDING_INVALID",
                    "A chain manager cannot also be a worker target.",
                    loaded.source()
                );
            }

            BoundedJsonSupport json = new BoundedJsonSupport(
                context.canonicalJson(),
                context.bounds()
            );
            SpecialistChainComponentId inputAdapterId = componentId(
                chainId,
                "input"
            );
            var inputAdapter = new DeclarativeSpecialistChainInputAdapter(
                inputAdapterId,
                inputSchema,
                context.schemaValidator(),
                json,
                spec.input().managerMessagePointer(),
                spec.input().managerContext()
            );

            List<SpecialistChainTarget<JsonNode, ?, ?>> targets =
                new ArrayList<>();
            int targetIndex = 0;
            for (SpecialistChainManifest.Target target : spec.targets()) {
                targets.add(compileTarget(
                    chainId,
                    target,
                    targetIndex++,
                    context,
                    json,
                    schemaDependencies,
                    loaded.source()
                ));
            }

            SpecialistChainDefinition<JsonNode> definition =
                new SpecialistChainDefinition<>(
                    chainId,
                    managerId,
                    JsonNode.class,
                    inputAdapter,
                    targets,
                    spec.limits(),
                    spec.conversationPolicy()
                );
            String semanticHash = context.canonicalJson().hashValue(
                normalizedSemantics(chainId, spec)
            );
            SpecialistChainRegistration registration =
                new SpecialistChainRegistration(
                definition,
                ai.fabric.execution.chain.SpecialistChainDefinitionSource
                    .MANIFEST,
                SpecialistChainRegistrationIdentity.manifest(
                    loaded.resourceHash(),
                    semanticHash,
                    schemaDependencies,
                    loaded.source()
                )
            );
            validateEffectiveRegistration(registration, context, loaded.source());
            return registration;
        } catch (SpecialistManifestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(
                "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                "The specialist-chain manifest could not be compiled safely.",
                loaded.source(),
                ex
            );
        }
    }

    private void validateEffectiveRegistration(
        SpecialistChainRegistration registration,
        SpecialistChainCompilationContext context,
        String source
    ) {
        var deployment = context.deployment();
        try {
            new DefaultSpecialistChainRegistry(
                new SpecialistChainRegistrationBundle(
                    List.of(registration),
                    List.of(),
                    1,
                    1
                ),
                context.specialistRegistry(),
                context.clientFactory(),
                context.canonicalJson(),
                deployment.getMaxDuration(),
                deployment.getMaxManagerDecisions(),
                deployment.getMaxWorkerInvocations(),
                deployment.getMaxParallelWorkers(),
                deployment.getMaxInvocationsPerTarget(),
                deployment.getMaxProjectedResultCharacters()
            );
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage();
            String reason;
            if (message.contains("read-only")) {
                reason = "CHAIN_MANIFEST_TARGET_NOT_READ_ONLY";
            } else if (message.contains("non-interactive")
                || message.contains("request user input")) {
                reason = "CHAIN_MANIFEST_TARGET_INTERACTIVE";
            } else if (message.contains("target enum")
                || message.contains("allowlist")) {
                reason = "CHAIN_MANIFEST_MANAGER_TARGET_CATALOG_MISMATCH";
            } else if (message.contains("manager")) {
                reason = "CHAIN_MANIFEST_MANAGER_CONTRACT_INVALID";
            } else if (message.contains("deployment ceiling")) {
                reason = "CHAIN_MANIFEST_LIMIT_EXCEEDS_DEPLOYMENT";
            } else if (message.contains("limit")
                || message.contains("maxParallelWorkers")) {
                reason = "CHAIN_MANIFEST_LIMIT_INVALID";
            } else {
                reason = "CHAIN_MANIFEST_TARGET_BINDING_INVALID";
            }
            throw failure(
                reason,
                "The compiled chain is incompatible with its resolved contracts.",
                source,
                ex
            );
        }
    }

    private SpecialistChainTarget<JsonNode, JsonNode, JsonNode> compileTarget(
        SpecialistChainId chainId,
        SpecialistChainManifest.Target target,
        int targetIndex,
        SpecialistChainCompilationContext context,
        BoundedJsonSupport json,
        Map<SpecialistSchemaId, String> schemaDependencies,
        String source
    ) {
        SpecialistId specialistId = parseSpecialist(
            target.specialistRef(),
            "CHAIN_MANIFEST_TARGET_NOT_FOUND",
            source
        );
        RegisteredSpecialist worker = requireSpecialist(
            context,
            specialistId,
            "CHAIN_MANIFEST_TARGET_NOT_FOUND",
            source
        );
        if (!(worker.definition().inputAdapter()
                instanceof JsonSchemaSpecialistInputAdapter inputAdapter)
            || !(worker.definition().outputAdapter()
                instanceof JsonSchemaSpecialistOutputAdapter outputAdapter)) {
            throw failure(
                "CHAIN_MANIFEST_TARGET_BINDING_INVALID",
                "Declarative chain targets require schema-backed JSON input and output.",
                source
            );
        }
        addSchemaDependency(
            schemaDependencies,
            inputAdapter.schemaDefinition(),
            context
        );
        addSchemaDependency(
            schemaDependencies,
            outputAdapter.schemaDefinition(),
            context
        );
        SpecialistChainComponentId mapperId = componentId(
            chainId,
            "target-" + targetIndex + "-input"
        );
        SpecialistChainComponentId projectorId = componentId(
            chainId,
            "target-" + targetIndex + "-result"
        );
        var mapper = new DeclarativeSpecialistChainTargetInputMapper(
            mapperId,
            target.input().fields(),
            inputAdapter.schemaDefinition(),
            context.schemaValidator(),
            context.objectMapper(),
            json,
            metrics
        );
        var projector = new DeclarativeSpecialistChainTargetResultProjector(
            projectorId,
            outputAdapter.schemaDefinition(),
            context.schemaValidator(),
            json,
            target.result().summaryPointer(),
            target.result().facts(),
            target.result().evidenceReferences(),
            metrics
        );
        SpecialistChainManifest.Transitions transitions = target.transitions();
        return new SpecialistChainTarget<>(
            specialistId,
            target.description(),
            mapper,
            projector,
            transitions.delegationAllowed(),
            transitions.parallelEligible(),
            transitions.handoffAllowed()
        );
    }

    private void addSchemaDependency(
        Map<SpecialistSchemaId, String> dependencies,
        SpecialistSchemaDefinition schema,
        SpecialistChainCompilationContext context
    ) {
        String hash = context.canonicalJson().hashValue(schema);
        String existing = dependencies.putIfAbsent(schema.id(), hash);
        if (existing != null && !existing.equals(hash)) {
            throw new IllegalArgumentException(
                "One exact schema ID resolved to conflicting content"
            );
        }
    }

    private void validateEnvelope(
        SpecialistChainManifest manifest,
        String source
    ) {
        if (manifest == null
            || !"ai.fabric/v1".equals(manifest.apiVersion())) {
            throw failure(
                "CHAIN_MANIFEST_API_VERSION_UNSUPPORTED",
                "Only ai.fabric/v1 specialist-chain resources are supported.",
                source
            );
        }
        if (!"SpecialistChain".equals(manifest.kind())) {
            throw failure(
                "CHAIN_MANIFEST_KIND_UNSUPPORTED",
                "Chain resources must use kind SpecialistChain.",
                source
            );
        }
        if (manifest.metadata() == null || manifest.spec() == null) {
            throw failure(
                "CHAIN_MANIFEST_ID_INVALID",
                "Chain metadata and spec are required.",
                source
            );
        }
    }

    private SpecialistChainId chainId(
        SpecialistChainManifest manifest,
        String source
    ) {
        String name = text(manifest.metadata().name());
        String version = text(manifest.metadata().version());
        if (name == null || version == null
            || !NAME.matcher(name).matches()
            || !VERSION.matcher(version).matches()) {
            throw failure(
                "CHAIN_MANIFEST_ID_INVALID",
                "Chain metadata must contain a valid exact name and version.",
                source
            );
        }
        return new SpecialistChainId(name, version);
    }

    private void validateMetadata(
        SpecialistChainManifest.Metadata metadata,
        String source
    ) {
        String displayName = text(metadata.displayName());
        String description = text(metadata.description());
        if (displayName == null || displayName.length() > 120
            || description == null || description.length() > 1_000
            || metadata.labels().size() > 16) {
            throw failure(
                "CHAIN_MANIFEST_ID_INVALID",
                "Chain metadata exceeds its published bounds.",
                source
            );
        }
        metadata.labels().forEach((name, value) -> {
            if (name == null || !LABEL_NAME.matcher(name).matches()
                || text(value) == null || value.trim().length() > 120) {
                throw failure(
                    "CHAIN_MANIFEST_ID_INVALID",
                    "Chain labels must use bounded descriptive keys and values.",
                    source
                );
            }
        });
    }

    private void validateStructure(
        SpecialistChainManifest.Spec spec,
        SpecialistChainDeclarativeBounds bounds,
        String source
    ) {
        if (spec.input() == null
            || spec.manager() == null
            || spec.limits() == null
            || spec.conversationPolicy() == null) {
            throw failure(
                "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                "Chain input, manager, limits, and conversation policy are required.",
                source
            );
        }
        if (spec.targets().isEmpty()
            || spec.targets().size()
                > ai.fabric.execution.chain.SpecialistChainManagerInput
                    .MAX_TARGETS) {
            throw failure(
                "CHAIN_MANIFEST_TARGET_NOT_FOUND",
                "A chain must declare a bounded non-empty target catalogue.",
                source
            );
        }
        if (spec.input().managerContext().size()
            > Math.min(
                bounds.maxManagerContextEntries(),
                ai.fabric.execution.chain.SpecialistChainManagerInput
                    .MAX_CONTEXT_VALUES
            )) {
            throw failure(
                "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                "Manager context exceeds its configured limit.",
                source
            );
        }
        Set<String> contextNames = new LinkedHashSet<>();
        BoundedJsonSupport json = new BoundedJsonSupport(
            new ai.fabric.execution.specialist.manifest.CanonicalJsonSupport(
                new com.fasterxml.jackson.databind.ObjectMapper()
            ),
            bounds
        );
        try {
            json.validatePointer(
                spec.input().managerMessagePointer(),
                "managerMessagePointer"
            );
        } catch (RuntimeException ex) {
            throw failure(
                "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                "The manager message selector is invalid.",
                source,
                ex
            );
        }
        for (SpecialistChainManifest.ContextValue value
            : spec.input().managerContext()) {
            if (value == null || !contextNames.add(value.name())) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                    "Manager context names must be present and unique.",
                    source
                );
            }
            new ConversationManagerContextValue(value.name(), "validation");
            try {
                json.validatePointer(
                    value.valuePointer(),
                    "managerContext.valuePointer"
                );
            } catch (RuntimeException ex) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_ADAPTER_INVALID",
                    "A manager context selector is invalid.",
                    source,
                    ex
                );
            }
        }
        Set<String> targetIds = new LinkedHashSet<>();
        int totalMappings = 0;
        for (SpecialistChainManifest.Target target : spec.targets()) {
            if (target == null
                || target.input() == null
                || target.result() == null
                || target.transitions() == null) {
                throw failure(
                    "CHAIN_MANIFEST_TARGET_BINDING_INVALID",
                    "Every chain target requires input, result, and transition declarations.",
                    source
                );
            }
            if (!targetIds.add(target.specialistRef())) {
                throw failure(
                    "CHAIN_MANIFEST_TARGET_DUPLICATE",
                    "A specialist may appear only once in a chain.",
                    source
                );
            }
            if (target.input().type()
                != SpecialistChainInputMappingType.JSON_POINTER_MAP) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_MAPPING_INVALID",
                    "V1 target input must use JSON_POINTER_MAP.",
                    source
                );
            }
            int mappings = target.input().fields().size();
            if (mappings < 1 || mappings > bounds.maxMappingsPerTarget()) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_MAPPING_LIMIT_EXCEEDED",
                    "Target input mappings exceed their configured limit.",
                    source
                );
            }
            totalMappings += mappings;
            validateMappings(target.input().fields(), bounds, source);
            validateProjection(target.result(), bounds, source);
            if (target.transitions().delegationAllowed() == null
                || target.transitions().parallelEligible() == null
                || target.transitions().handoffAllowed() == null) {
                throw failure(
                    "CHAIN_MANIFEST_TARGET_BINDING_INVALID",
                    "All target transition flags must be explicit.",
                    source
                );
            }
            boolean delegation = target.transitions().delegationAllowed();
            boolean parallel = target.transitions().parallelEligible();
            boolean handoff = target.transitions().handoffAllowed();
            if ((!delegation && !handoff) || (parallel && !delegation)) {
                throw failure(
                    "CHAIN_MANIFEST_TARGET_BINDING_INVALID",
                    "Target transitions must expose an approved bounded path.",
                    source
                );
            }
        }
        if (totalMappings > bounds.maxMappingsPerChain()) {
            throw failure(
                "CHAIN_MANIFEST_INPUT_MAPPING_LIMIT_EXCEEDED",
                "Chain input mappings exceed their configured limit.",
                source
            );
        }
    }

    private void validateMappings(
        List<SpecialistChainManifest.MappingField> fields,
        SpecialistChainDeclarativeBounds bounds,
        String source
    ) {
        BoundedJsonSupport json = new BoundedJsonSupport(
            new ai.fabric.execution.specialist.manifest.CanonicalJsonSupport(
                new com.fasterxml.jackson.databind.ObjectMapper()
            ),
            bounds
        );
        Set<String> targetFields = new LinkedHashSet<>();
        for (SpecialistChainManifest.MappingField field : fields) {
            if (field == null || field.source() == null) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_MAPPING_INVALID",
                    "Every input mapping requires an approved source.",
                    source
                );
            }
            String targetField;
            try {
                targetField = json.validateTargetField(field.targetField());
                if (field.source()
                    == SpecialistChainMappingSource.CHAIN_INPUT) {
                    json.validatePointer(
                        field.sourcePointer(),
                        "mapping.sourcePointer"
                    );
                } else if (field.sourcePointer() != null
                    && !field.sourcePointer().isBlank()) {
                    throw new IllegalArgumentException(
                        "Manager-objective mappings cannot declare a pointer"
                    );
                }
            } catch (RuntimeException ex) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_MAPPING_INVALID",
                    "A target input mapping is invalid.",
                    source,
                    ex
                );
            }
            if (!targetFields.add(targetField)) {
                throw failure(
                    "CHAIN_MANIFEST_INPUT_MAPPING_INVALID",
                    "Target input fields must be unique.",
                    source
                );
            }
        }
    }

    private void validateProjection(
        SpecialistChainManifest.TargetResult result,
        SpecialistChainDeclarativeBounds bounds,
        String source
    ) {
        if (result.type()
                != SpecialistChainResultProjectionType
                    .BOUNDED_FACT_PROJECTION
            || result.evidenceReferences() == null
            || result.facts().size()
                > ai.fabric.execution.chain.SpecialistChainResultProjection
                    .MAX_FACTS) {
            throw failure(
                "CHAIN_MANIFEST_RESULT_PROJECTION_INVALID",
                "V1 results require a bounded fact projection.",
                source
            );
        }
        BoundedJsonSupport json = new BoundedJsonSupport(
            new ai.fabric.execution.specialist.manifest.CanonicalJsonSupport(
                new com.fasterxml.jackson.databind.ObjectMapper()
            ),
            bounds
        );
        try {
            json.validatePointer(result.summaryPointer(), "summaryPointer");
            Set<String> names = new LinkedHashSet<>();
            for (SpecialistChainManifest.FactField fact : result.facts()) {
                if (fact == null
                    || text(fact.name()) == null
                    || !names.add(fact.name().trim())) {
                    throw new IllegalArgumentException(
                        "Fact names must be present and unique"
                    );
                }
                new ai.fabric.execution.chain.SpecialistChainResultProjection(
                    "validation",
                    Map.of(fact.name(), "validation"),
                    List.of()
                );
                json.validatePointer(
                    fact.valuePointer(),
                    "fact.valuePointer"
                );
            }
        } catch (RuntimeException ex) {
            throw failure(
                "CHAIN_MANIFEST_RESULT_PROJECTION_INVALID",
                "A target result projection is invalid.",
                source,
                ex
            );
        }
    }

    private RegisteredSpecialist requireSpecialist(
        SpecialistChainCompilationContext context,
        SpecialistId id,
        String reason,
        String source
    ) {
        return context.specialistRegistry().findRegistered(id).orElseThrow(() ->
            failure(
                reason,
                "An exact specialist reference is not registered.",
                source
            )
        );
    }

    private SpecialistId parseSpecialist(
        String reference,
        String reason,
        String source
    ) {
        try {
            return SpecialistId.parse(reference);
        } catch (RuntimeException ex) {
            throw failure(
                reason,
                "A specialist reference must use exact name@version syntax.",
                source,
                ex
            );
        }
    }

    private SpecialistChainComponentId componentId(
        SpecialistChainId chainId,
        String role
    ) {
        return SpecialistChainComponentId.of(
            "manifest-" + chainId.name() + "-" + role,
            chainId.version()
        );
    }

    private Map<String, Object> normalizedSemantics(
        SpecialistChainId chainId,
        SpecialistChainManifest.Spec spec
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("contractVersion", "ai.fabric/specialist-chain-v1");
        value.put("id", chainId.toString());
        value.put("inputSchema", spec.input().schemaRef());
        value.put("managerMessagePointer", spec.input().managerMessagePointer());
        value.put("managerContext", spec.input().managerContext().stream()
            .map(context -> Map.of(
                "name", context.name(),
                "valuePointer", context.valuePointer(),
                "required", context.requiredOrDefault()
            )).toList());
        value.put("manager", spec.manager().specialistRef());
        value.put("targets", spec.targets().stream()
            .map(this::normalizedTarget)
            .toList());
        value.put("limits", spec.limits());
        value.put("conversationPolicy", spec.conversationPolicy().name());
        return Map.copyOf(value);
    }

    private Map<String, Object> normalizedTarget(
        SpecialistChainManifest.Target target
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("specialist", target.specialistRef());
        value.put("description", target.description());
        value.put("inputType", target.input().type().name());
        value.put("inputFields", target.input().fields().stream()
            .map(field -> {
                LinkedHashMap<String, Object> mapped = new LinkedHashMap<>();
                mapped.put("source", field.source().name());
                mapped.put(
                    "sourcePointer",
                    field.sourcePointer() == null ? "" : field.sourcePointer()
                );
                mapped.put("targetField", field.targetField());
                mapped.put("required", field.requiredOrDefault());
                return Map.copyOf(mapped);
            }).toList());
        value.put("resultType", target.result().type().name());
        value.put("summaryPointer", target.result().summaryPointer());
        value.put("facts", target.result().facts().stream()
            .map(fact -> Map.of(
                "name", fact.name(),
                "valuePointer", fact.valuePointer(),
                "required", fact.requiredOrDefault()
            )).toList());
        value.put(
            "evidenceReferences",
            target.result().evidenceReferences().name()
        );
        value.put("delegationAllowed", target.transitions().delegationAllowed());
        value.put("parallelEligible", target.transitions().parallelEligible());
        value.put("handoffAllowed", target.transitions().handoffAllowed());
        return Map.copyOf(value);
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private SpecialistManifestException failure(
        String reason,
        String message,
        String source
    ) {
        return new SpecialistManifestException(reason, message, source);
    }

    private SpecialistManifestException failure(
        String reason,
        String message,
        String source,
        Throwable cause
    ) {
        return new SpecialistManifestException(
            reason,
            message,
            source,
            cause
        );
    }
}
