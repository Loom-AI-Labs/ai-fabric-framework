package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistDelegationPolicy;
import ai.fabric.execution.specialist.SpecialistExecutionProfile;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistIdentity;
import ai.fabric.execution.specialist.SpecialistInstructions;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class DefaultSpecialistManifestCompiler
    implements SpecialistManifestCompiler {

    private static final Pattern NAME = Pattern.compile(
        "[a-z][a-z0-9-]{0,79}"
    );
    private static final Pattern VERSION = Pattern.compile(
        "[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
    );
    private static final Pattern LABEL_KEY = Pattern.compile(
        "[a-z][a-z0-9.-]{0,62}"
    );
    @Override
    public SpecialistCompilationResult compile(
        SpecialistManifest manifest,
        SpecialistCompilationContext context
    ) {
        Objects.requireNonNull(manifest, "manifest is required");
        Objects.requireNonNull(context, "context is required");
        try {
            validateEnvelope(manifest, context.source());
            SpecialistManifestMetadata metadata = manifest.metadata();
            SpecialistManifestSpec spec = manifest.spec();
            validateMetadata(metadata, context.source());
            requireComponents(spec, context.source());

            SpecialistPromptProfile prompt = context.promptProfileRegistry()
                .require(SpecialistPromptProfileId.parse(
                    spec.instructions().promptProfileRef()
                ));
            SpecialistSchemaDefinition inputSchema = context.schemaRegistry()
                .require(
                    SpecialistSchemaId.parse(spec.input().schemaRef()),
                    SpecialistSchemaDirection.INPUT
                );
            SpecialistSchemaDefinition outputSchema = context.schemaRegistry()
                .require(
                    SpecialistSchemaId.parse(spec.output().schemaRef()),
                    SpecialistSchemaDirection.OUTPUT
                );

            RequestedCapabilityProfile capabilities = capabilities(
                spec,
                context.source()
            );
            SpecialistLimits limits = limits(spec.limits(), context.source());
            validateExecution(spec, context, capabilities);
            validateInput(spec.input(), context.source());
            validateGrounding(
                spec.grounding(),
                capabilities,
                context.source()
            );
            validateOutput(spec.output(), context.source());
            validateConversation(
                spec.input(),
                spec.output(),
                spec.conversation(),
                context.source()
            );

            List<SpecialistGroundingValidator> groundingValidators =
                spec.grounding().validatorRefs().stream()
                    .map(context.groundingValidatorRegistry()::require)
                    .toList();
            List<SpecialistFinalOutputValidator> finalValidators =
                spec.output().finalValidatorRefs().stream()
                    .map(context.finalOutputValidatorRegistry()::require)
                    .toList();
            SpecialistDirectOutputProjector directProjector =
                spec.output().mode() == SpecialistOutputMode.DIRECT_PROJECTION
                    ? context.directOutputProjectorRegistry().require(
                        spec.output().directProjectorRef()
                    )
                    : null;
            SpecialistOutputNormalizer normalizer =
                spec.output().normalizerRef() == null
                    ? null
                    : context.outputNormalizerRegistry().require(
                        spec.output().normalizerRef()
                    );
            SpecialistInputContinuation<
                com.fasterxml.jackson.databind.JsonNode
            > inputContinuation = inputContinuation(spec.input(), context);

            SpecialistDefinition<
                com.fasterxml.jackson.databind.JsonNode,
                com.fasterxml.jackson.databind.JsonNode
            > definition = new SpecialistDefinition<>(
                new SpecialistIdentity(
                    SpecialistId.of(metadata.name(), metadata.version()),
                    requireText(
                        metadata.displayName(),
                        "metadata.displayName",
                        context.source()
                    ),
                    requireText(
                        metadata.description(),
                        "metadata.description",
                        context.source()
                    )
                ),
                new SpecialistInstructions(
                    spec.instructions().objective(),
                    prompt.spec().constraints()
                ),
                new SpecialistExecutionProfile(
                    spec.mode().trim().toLowerCase(Locale.ROOT),
                    capabilities,
                    spec.execution().strategy(),
                    spec.execution().writePolicy()
                ),
                limits,
                delegation(spec.delegation(), context.source()),
                new JsonSchemaSpecialistInputAdapter(
                    inputSchema,
                    spec.input(),
                    spec.conversation(),
                    inputContinuation,
                    limits,
                    context.schemaValidator(),
                    context.canonicalJson(),
                    context.objectMapper()
                ),
                new JsonSchemaSpecialistOutputAdapter(
                    outputSchema,
                    spec.output(),
                    spec.grounding(),
                    capabilities,
                    prompt.spec().outputContract(),
                    context.schemaValidator(),
                    context.canonicalJson(),
                    new DefaultManifestGroundingValidator(),
                    groundingValidators,
                    finalValidators,
                    directProjector,
                    normalizer
                )
            );
            context.definitionValidator().validate(definition);
            return new SpecialistCompilationResult(
                new RegisteredSpecialist(
                    definition,
                    SpecialistDefinitionSource.MANIFEST,
                    context.contentHash(),
                    context.source(),
                    metadata.labels()
                ),
                List.of()
            );
        } catch (SpecialistManifestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SpecialistManifestException(
                "MANIFEST_COMPILATION_FAILED",
                safeMessage(ex),
                context.source(),
                ex
            );
        }
    }

    private void validateEnvelope(
        SpecialistManifest manifest,
        String source
    ) {
        if (!"ai.fabric/v1".equals(manifest.apiVersion())) {
            throw failure(
                "RESOURCE_API_VERSION_UNSUPPORTED",
                "Only ai.fabric/v1 specialist resources are supported.",
                source
            );
        }
        if (!"Specialist".equals(manifest.kind())) {
            throw failure(
                "RESOURCE_KIND_INVALID",
                "Specialist manifests must use kind Specialist.",
                source
            );
        }
        if (manifest.metadata() == null || manifest.spec() == null) {
            throw failure(
                "MANIFEST_INCOMPLETE",
                "Specialist metadata and spec are required.",
                source
            );
        }
    }

    private void validateMetadata(
        SpecialistManifestMetadata metadata,
        String source
    ) {
        if (!NAME.matcher(requireText(
                metadata.name(),
                "metadata.name",
                source
            )).matches()) {
            throw failure(
                "SPECIALIST_NAME_INVALID",
                "Specialist names must use lowercase letters, digits, and hyphens.",
                source
            );
        }
        if (!VERSION.matcher(requireText(
                metadata.version(),
                "metadata.version",
                source
            )).matches()) {
            throw failure(
                "SPECIALIST_VERSION_INVALID",
                "Specialist versions contain unsupported characters.",
                source
            );
        }
        bounded(metadata.displayName(), 120, "metadata.displayName", source);
        bounded(metadata.description(), 1_000, "metadata.description", source);
        if (metadata.labels().size() > 16) {
            throw failure(
                "SPECIALIST_LABEL_LIMIT_EXCEEDED",
                "A specialist may define at most 16 labels.",
                source
            );
        }
        for (Map.Entry<String, String> label : metadata.labels().entrySet()) {
            if (label.getKey() == null
                || !LABEL_KEY.matcher(label.getKey()).matches()
                || label.getValue() == null
                || label.getValue().isBlank()
                || label.getValue().length() > 120) {
                throw failure(
                    "SPECIALIST_LABEL_INVALID",
                    "Specialist labels must be bounded operational metadata.",
                    source
                );
            }
        }
    }

    private void requireComponents(
        SpecialistManifestSpec spec,
        String source
    ) {
        requireText(spec.mode(), "spec.mode", source);
        Objects.requireNonNull(spec.instructions(), "instructions is required");
        Objects.requireNonNull(spec.execution(), "execution is required");
        Objects.requireNonNull(spec.capabilities(), "capabilities is required");
        Objects.requireNonNull(spec.capabilities().retrieval(), "retrieval is required");
        Objects.requireNonNull(spec.capabilities().actions(), "actions is required");
        Objects.requireNonNull(spec.input(), "input is required");
        Objects.requireNonNull(spec.grounding(), "grounding is required");
        Objects.requireNonNull(spec.output(), "output is required");
        Objects.requireNonNull(spec.conversation(), "conversation is required");
        Objects.requireNonNull(spec.limits(), "limits is required");
        bounded(
            spec.instructions().objective(),
            1_000,
            "instructions.objective",
            source
        );
        requireText(
            spec.instructions().promptProfileRef(),
            "instructions.promptProfileRef",
            source
        );
    }

    private SpecialistDelegationPolicy delegation(
        SpecialistDelegationSpec spec,
        String source
    ) {
        List<String> references = spec == null
            ? List.of()
            : spec.targets();
        rejectDuplicates(references, "delegation targets", source);
        if (references.size() > SpecialistDelegationPolicy.MAX_TARGETS) {
            throw failure(
                "DELEGATION_TARGET_LIMIT_EXCEEDED",
                "A specialist may declare at most "
                    + SpecialistDelegationPolicy.MAX_TARGETS
                    + " delegation targets.",
                source
            );
        }
        Set<SpecialistId> targets = new LinkedHashSet<>();
        for (String reference : references) {
            try {
                targets.add(SpecialistId.parse(reference));
            } catch (IllegalArgumentException ex) {
                throw failure(
                    "DELEGATION_TARGET_INVALID",
                    "Delegation targets must use exact name@version references.",
                    source
                );
            }
        }
        return SpecialistDelegationPolicy.oneLevel(targets);
    }

    private RequestedCapabilityProfile capabilities(
        SpecialistManifestSpec spec,
        String source
    ) {
        SpecialistRetrievalSpec retrieval = spec.capabilities().retrieval();
        SpecialistActionSpec actions = spec.capabilities().actions();
        rejectDuplicates(retrieval.vectorSpaces(), "vectorSpaces", source);
        rejectDuplicates(actions.visible(), "visible actions", source);
        rejectDuplicates(
            actions.requestableReads(),
            "requestable READ actions",
            source
        );
        rejectDuplicates(
            actions.proposableWrites(),
            "proposable WRITE actions",
            source
        );
        if (retrieval.enabled() && retrieval.vectorSpaces().isEmpty()) {
            throw failure(
                "RETRIEVAL_SCOPE_REQUIRED",
                "Enabled retrieval requires at least one vector space.",
                source
            );
        }
        if (!retrieval.enabled() && !retrieval.vectorSpaces().isEmpty()) {
            throw failure(
                "RETRIEVAL_SCOPE_UNUSED",
                "Disabled retrieval cannot declare vector spaces.",
                source
            );
        }
        return new RequestedCapabilityProfile(
            retrieval.enabled(),
            new LinkedHashSet<>(retrieval.vectorSpaces()),
            new LinkedHashSet<>(actions.visible()),
            new LinkedHashSet<>(actions.requestableReads()),
            new LinkedHashSet<>(actions.proposableWrites())
        );
    }

    private void validateExecution(
        SpecialistManifestSpec spec,
        SpecialistCompilationContext context,
        RequestedCapabilityProfile capabilities
    ) {
        if (spec.execution().strategy() == null
            || spec.execution().writePolicy() == null) {
            throw failure(
                "EXECUTION_POLICY_REQUIRED",
                "Execution strategy and write policy are required.",
                context.source()
            );
        }
        if (spec.execution().strategy() == ExecutionStrategy.DIRECT) {
            throw failure(
                "EXECUTION_STRATEGY_UNSUPPORTED",
                "DIRECT is not implemented for manifest specialists.",
                context.source()
            );
        }
        String mode = spec.mode().trim().toLowerCase(Locale.ROOT);
        if (spec.execution().strategy() == ExecutionStrategy.BOUNDED_ITERATIVE
            && !context.iterativeModes().contains(mode)) {
            throw failure(
                "ITERATIVE_MODE_REQUIRED",
                "BOUNDED_ITERATIVE requires a Mode with iterative read-action planning.",
                context.source()
            );
        }
        if (!capabilities.proposableWriteActions().isEmpty()
            && spec.execution().writePolicy()
                != SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED) {
            throw failure(
                "WRITE_POLICY_INVALID",
                "Write proposals require CONFIRMATION_RECEIPT_REQUIRED.",
                context.source()
            );
        }
        if (capabilities.proposableWriteActions().isEmpty()
            && spec.execution().writePolicy()
                == SpecialistWritePolicy.CONFIRMATION_RECEIPT_REQUIRED) {
            throw failure(
                "WRITE_POLICY_UNUSED",
                "CONFIRMATION_RECEIPT_REQUIRED requires at least one proposable write.",
                context.source()
            );
        }
    }

    private void validateInput(SpecialistInputSpec input, String source) {
        requireText(input.schemaRef(), "input.schemaRef", source);
        if (input.continuationRef() != null) {
            requireText(
                input.continuationRef(),
                "input.continuationRef",
                source
            );
        }
        if (input.rendering()
            != SpecialistInputRendering.PRIMARY_TEXT_WITH_JSON_CONTEXT) {
            throw failure(
                "INPUT_RENDERING_UNSUPPORTED",
                "Only PRIMARY_TEXT_WITH_JSON_CONTEXT is supported.",
                source
            );
        }
        pointer(input.primaryTextPointer(), "input.primaryTextPointer", source);
        optionalPointer(
            input.conversationTextPointer(),
            "input.conversationTextPointer",
            source
        );
        rejectDuplicates(input.contextPointers(), "context pointers", source);
        input.contextPointers().forEach(value ->
            pointer(value, "input.contextPointers", source)
        );
        if (input.context() != null
            && input.context().position() != null) {
            bounded(
                input.context().position(),
                80,
                "input.context.position",
                source
            );
        }
    }

    @SuppressWarnings("unchecked")
    private SpecialistInputContinuation<
        com.fasterxml.jackson.databind.JsonNode
    > inputContinuation(
        SpecialistInputSpec input,
        SpecialistCompilationContext context
    ) {
        if (input.continuationRef() == null
            || input.continuationRef().isBlank()) {
            return null;
        }
        SpecialistInputContinuation<?> continuation =
            context.inputContinuationRegistry().require(
                input.continuationRef()
            );
        if (continuation.inputType()
            != com.fasterxml.jackson.databind.JsonNode.class) {
            throw failure(
                "INPUT_CONTINUATION_TYPE_MISMATCH",
                "Manifest input continuations must accept JsonNode input.",
                context.source()
            );
        }
        continuation.responseSchemas().forEach(schemaId ->
            context.schemaRegistry().require(
                schemaId,
                SpecialistSchemaDirection.INPUT
            )
        );
        return (SpecialistInputContinuation<
            com.fasterxml.jackson.databind.JsonNode
        >) continuation;
    }

    private void validateGrounding(
        SpecialistGroundingSpec grounding,
        RequestedCapabilityProfile capabilities,
        String source
    ) {
        if (grounding.requirement() == null) {
            throw failure(
                "GROUNDING_REQUIREMENT_REQUIRED",
                "A grounding requirement is required.",
                source
            );
        }
        boolean groundedCapability =
            capabilities.retrievalEnabled()
                || !capabilities.requestableReadActions().isEmpty();
        if (grounding.requirement() == SpecialistGroundingRequirement.NONE
            && groundedCapability) {
            throw failure(
                "GROUNDING_POLICY_TOO_WEAK",
                "A specialist using retrieval or read actions cannot disable grounding.",
                source
            );
        }
        if (grounding.requirement() == SpecialistGroundingRequirement.REQUIRED
            && grounding.sources().isEmpty()) {
            throw failure(
                "GROUNDING_SOURCE_REQUIRED",
                "Required grounding needs at least one bounded source.",
                source
            );
        }
        rejectDuplicates(
            grounding.validatorRefs(),
            "grounding validator refs",
            source
        );
        Set<String> sourceKeys = new HashSet<>();
        for (SpecialistGroundingSourceSpec item : grounding.sources()) {
            if (item == null || item.type() == null || item.minimumCount() < 1) {
                throw failure(
                    "GROUNDING_SOURCE_INVALID",
                    "Grounding sources require a type and positive minimum count.",
                    source
                );
            }
            String key = item.type() + ":" + item.name();
            if (!sourceKeys.add(key)) {
                throw failure(
                    "GROUNDING_SOURCE_DUPLICATE",
                    "Grounding source declarations must be unique.",
                    source
                );
            }
            switch (item.type()) {
                case READ_ACTION -> {
                    String name = requireText(
                        item.name(),
                        "grounding.source.name",
                        source
                    );
                    if (!capabilities.requestableReadActions().contains(name)) {
                        throw failure(
                            "GROUNDING_READ_ACTION_NOT_REQUESTED",
                            "A READ_ACTION grounding source must be requestable by the specialist.",
                            source
                        );
                    }
                    if (!item.requiredEvidenceIds().isEmpty()) {
                        throw failure(
                            "GROUNDING_EVIDENCE_IDS_INVALID",
                            "Required evidence IDs apply only to vector sources.",
                            source
                        );
                    }
                }
                case VECTOR_SPACE -> {
                    String name = requireText(
                        item.name(),
                        "grounding.source.name",
                        source
                    ).toLowerCase(Locale.ROOT);
                    if (!capabilities.requestedVectorSpaces().contains(name)) {
                        throw failure(
                            "GROUNDING_VECTOR_SPACE_NOT_REQUESTED",
                            "A VECTOR_SPACE grounding source must be requested by the specialist.",
                            source
                        );
                    }
                }
                case ANY_ALLOWED_VECTOR_SPACE -> {
                    if (item.name() != null && !item.name().isBlank()) {
                        throw failure(
                            "GROUNDING_SOURCE_NAME_UNUSED",
                            "ANY_ALLOWED_VECTOR_SPACE does not accept a name.",
                            source
                        );
                    }
                    if (!capabilities.retrievalEnabled()) {
                        throw failure(
                            "GROUNDING_RETRIEVAL_REQUIRED",
                            "ANY_ALLOWED_VECTOR_SPACE requires retrieval.",
                            source
                        );
                    }
                }
            }
            rejectDuplicates(
                item.requiredEvidenceIds(),
                "required evidence IDs",
                source
            );
        }
    }

    private void validateOutput(SpecialistOutputSpec output, String source) {
        if (output.mode() == null) {
            throw failure(
                "OUTPUT_MODE_REQUIRED",
                "An output mode is required.",
                source
            );
        }
        requireText(output.schemaRef(), "output.schemaRef", source);
        optionalPointer(
            output.conversationTextPointer(),
            "output.conversationTextPointer",
            source
        );
        rejectDuplicates(
            output.finalValidatorRefs(),
            "final output validator refs",
            source
        );
        if (output.mode() == SpecialistOutputMode.STRUCTURED_GENERATION
            && output.directProjectorRef() != null) {
            throw failure(
                "OUTPUT_PROJECTOR_NOT_ALLOWED",
                "STRUCTURED_GENERATION cannot declare a direct projector.",
                source
            );
        }
        if (output.mode() == SpecialistOutputMode.DIRECT_PROJECTION
            && (output.directProjectorRef() == null
                || output.directProjectorRef().isBlank())) {
            throw failure(
                "OUTPUT_PROJECTOR_REQUIRED",
                "DIRECT_PROJECTION requires a registered projector.",
                source
            );
        }
    }

    private void validateConversation(
        SpecialistInputSpec input,
        SpecialistOutputSpec output,
        SpecialistConversationSpec conversation,
        String source
    ) {
        if (conversation.binding() == null) {
            throw failure(
                "CONVERSATION_BINDING_REQUIRED",
                "A conversation binding policy is required.",
                source
            );
        }
        if (conversation.binding() == SpecialistConversationBinding.DISABLED
            && conversation.recordValidatedTurns()) {
            throw failure(
                "CONVERSATION_RECORDING_INVALID",
                "Disabled conversation binding cannot record turns.",
                source
            );
        }
        if (conversation.recordValidatedTurns()
            && (input.conversationTextPointer() == null
                || output.conversationTextPointer() == null)) {
            throw failure(
                "CONVERSATION_POINTER_REQUIRED",
                "Recording validated turns requires input and output conversation pointers.",
                source
            );
        }
    }

    private SpecialistLimits limits(
        SpecialistLimitSpec limits,
        String source
    ) {
        SpecialistFrameworkLimits ceilings =
            SpecialistFrameworkLimits.DEFAULT;
        if (limits.maxDuration() == null
            || limits.maxDuration().isZero()
            || limits.maxDuration().isNegative()
            || limits.maxDuration().compareTo(ceilings.maxDuration()) > 0
            || limits.maxInputCharacters() < 1
            || limits.maxInputCharacters()
                > ceilings.maxInputCharacters()
            || limits.maxGroundingCharacters() < 1
            || limits.maxGroundingCharacters()
                > ceilings.maxGroundingCharacters()
            || limits.maxEvidenceReferences() < 0
            || limits.maxEvidenceReferences()
                > ceilings.maxEvidenceReferences()
            || limits.maxOutputCharacters() < 1
            || limits.maxOutputCharacters()
                > ceilings.maxOutputCharacters()
            || limits.maxOutputTokens() < 1
            || limits.maxOutputTokens() > ceilings.maxOutputTokens()) {
            throw failure(
                "SPECIALIST_LIMIT_INVALID",
                "Specialist limits must be positive and within framework ceilings.",
                source
            );
        }
        return new SpecialistLimits(
            limits.maxDuration(),
            limits.maxInputCharacters(),
            limits.maxGroundingCharacters(),
            limits.maxEvidenceReferences(),
            limits.maxOutputCharacters(),
            limits.maxOutputTokens()
        );
    }

    private void rejectDuplicates(
        List<String> values,
        String field,
        String source
    ) {
        if (values == null) {
            return;
        }
        Set<String> normalized = new HashSet<>();
        for (String value : values) {
            String item = requireText(value, field, source);
            if (!normalized.add(item.toLowerCase(Locale.ROOT))) {
                throw failure(
                    "MANIFEST_LIST_DUPLICATE",
                    "Duplicate values are not allowed in " + field + ".",
                    source
                );
            }
        }
    }

    private void pointer(String value, String field, String source) {
        String pointer = requireText(value, field, source);
        if (!pointer.startsWith("/")) {
            throw failure(
                "JSON_POINTER_INVALID",
                field + " must use an RFC 6901 JSON pointer.",
                source
            );
        }
        try {
            com.fasterxml.jackson.core.JsonPointer.compile(pointer);
        } catch (IllegalArgumentException ex) {
            throw failure(
                "JSON_POINTER_INVALID",
                field + " must use an RFC 6901 JSON pointer.",
                source
            );
        }
    }

    private void optionalPointer(
        String value,
        String field,
        String source
    ) {
        if (value != null) {
            pointer(value, field, source);
        }
    }

    private String bounded(
        String value,
        int maxCharacters,
        String field,
        String source
    ) {
        String normalized = requireText(value, field, source);
        if (normalized.length() > maxCharacters) {
            throw failure(
                "MANIFEST_TEXT_TOO_LARGE",
                field + " exceeds its character limit.",
                source
            );
        }
        return normalized;
    }

    private String requireText(String value, String field, String source) {
        if (value == null || value.isBlank()) {
            throw failure(
                "MANIFEST_FIELD_REQUIRED",
                field + " is required.",
                source
            );
        }
        return value.trim();
    }

    private String safeMessage(RuntimeException ex) {
        if (ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "The specialist manifest failed startup validation.";
        }
        String message = ex.getMessage().trim();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private SpecialistManifestException failure(
        String reason,
        String message,
        String source
    ) {
        return new SpecialistManifestException(reason, message, source);
    }
}
