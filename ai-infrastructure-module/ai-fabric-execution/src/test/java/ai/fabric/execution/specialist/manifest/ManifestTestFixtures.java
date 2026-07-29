package ai.fabric.execution.specialist.manifest;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.specialist.ExecutionStrategy;
import ai.fabric.execution.specialist.SpecialistDefinitionValidator;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.execution.specialist.SpecialistWritePolicy;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.intent.action.AIActionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ManifestTestFixtures {

    public static final String HASH = "a".repeat(64);

    private ManifestTestFixtures() {}

    public static ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    public static SpecialistManifest manifest() {
        return manifest("support-knowledge", "deep");
    }

    public static SpecialistManifest manifest(String name, String mode) {
        return new SpecialistManifest(
            "ai.fabric/v1",
            "Specialist",
            new SpecialistManifestMetadata(
                name,
                "1",
                "Support Knowledge",
                "Answers only from approved support evidence.",
                Map.of("domain", "support")
            ),
            new SpecialistManifestSpec(
                mode,
                new SpecialistInstructionSpec(
                    "Answer the support question using approved evidence.",
                    "grounded-support@1"
                ),
                new SpecialistExecutionSpec(
                    ExecutionStrategy.SINGLE_PASS,
                    SpecialistWritePolicy.DISABLED
                ),
                new SpecialistCapabilitySpec(
                    new SpecialistRetrievalSpec(
                        true,
                        List.of("support-policy")
                    ),
                    new SpecialistActionSpec(
                        List.of(),
                        List.of(),
                        List.of()
                    )
                ),
                new SpecialistInputSpec(
                    "support-question@1",
                    SpecialistInputRendering.PRIMARY_TEXT_WITH_JSON_CONTEXT,
                    "/question",
                    "/question",
                    List.of(),
                    new SpecialistInputContextSpec("support")
                ),
                new SpecialistGroundingSpec(
                    SpecialistGroundingRequirement.REQUIRED,
                    true,
                    List.of(new SpecialistGroundingSourceSpec(
                        SpecialistGroundingSourceType.ANY_ALLOWED_VECTOR_SPACE,
                        null,
                        1,
                        List.of(),
                        false
                    )),
                    List.of()
                ),
                new SpecialistOutputSpec(
                    SpecialistOutputMode.STRUCTURED_GENERATION,
                    "support-answer@1",
                    null,
                    "/answer",
                    List.of(),
                    null
                ),
                new SpecialistConversationSpec(
                    SpecialistConversationBinding.OPTIONAL,
                    true
                ),
                new SpecialistLimitSpec(
                    Duration.ofSeconds(30),
                    4_000,
                    12_000,
                    10,
                    8_000,
                    700
                )
            )
        );
    }

    public static SpecialistSchemaDefinition inputSchema() {
        ObjectMapper mapper = objectMapper();
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("required", mapper.createArrayNode().add("question"));
        schema.set(
            "properties",
            mapper.createObjectNode().set(
                "question",
                mapper.createObjectNode()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 500)
            )
        );
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata("support-question", "1"),
            new SpecialistSchemaSpec(
                SpecialistSchemaDirection.INPUT,
                "2020-12",
                schema
            )
        );
    }

    public static SpecialistSchemaDefinition outputSchema() {
        ObjectMapper mapper = objectMapper();
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("required", mapper.createArrayNode().add("answer"));
        schema.set(
            "properties",
            mapper.createObjectNode().set(
                "answer",
                mapper.createObjectNode()
                    .put("type", "string")
                    .put("minLength", 1)
                    .put("maxLength", 2_000)
            )
        );
        return new SpecialistSchemaDefinition(
            "ai.fabric/v1",
            "SpecialistSchema",
            new SpecialistResourceMetadata("support-answer", "1"),
            new SpecialistSchemaSpec(
                SpecialistSchemaDirection.OUTPUT,
                "2020-12",
                schema
            )
        );
    }

    public static SpecialistPromptProfile promptProfile() {
        return new SpecialistPromptProfile(
            "ai.fabric/v1",
            "SpecialistPromptProfile",
            new SpecialistResourceMetadata("grounded-support", "1"),
            new SpecialistPromptProfileSpec(
                "Use only supplied support evidence.",
                "Return one answer string grounded in the cited evidence."
            )
        );
    }

    public static SpecialistDefinitionValidator definitionValidator() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        when(actionRegistry.getAllMetadata()).thenReturn(List.of());
        return new SpecialistDefinitionValidator(
            actionRegistry,
            Set.of("deep"),
            Set.of("support-policy")
        );
    }

    public static SpecialistCompilationContext compilationContext() {
        return compilationContext(List.of(), List.of());
    }

    public static SpecialistCompilationContext compilationContext(
        List<SpecialistInputContinuation<?>> continuations,
        List<SpecialistSchemaDefinition> additionalSchemas
    ) {
        ObjectMapper mapper = objectMapper();
        SpecialistJsonSchemaValidator schemaValidator =
            new SpecialistJsonSchemaValidator();
        List<SpecialistSchemaDefinition> schemas = new ArrayList<>(
            List.of(inputSchema(), outputSchema())
        );
        schemas.addAll(
            additionalSchemas == null ? List.of() : additionalSchemas
        );
        return new SpecialistCompilationContext(
            new SpecialistJsonSchemaRegistry(
                schemas,
                schemaValidator
            ),
            new SpecialistPromptProfileRegistry(List.of(promptProfile())),
            new SpecialistGroundingValidatorRegistry(List.of()),
            new SpecialistFinalOutputValidatorRegistry(List.of()),
            new SpecialistDirectOutputProjectorRegistry(List.of()),
            new SpecialistOutputNormalizerRegistry(List.of()),
            new SpecialistInputContinuationRegistry(continuations),
            schemaValidator,
            definitionValidator(),
            new CanonicalJsonSupport(mapper),
            mapper,
            Set.of(),
            "support.yml#4",
            HASH
        );
    }

    public static SpecialistResourceBundle resourceBundle(
        SpecialistManifest manifest
    ) {
        return new SpecialistResourceBundle(
            List.of(new LoadedSpecialistManifest(
                manifest,
                HASH,
                "support.yml#4"
            )),
            List.of(inputSchema(), outputSchema()),
            List.of(promptProfile())
        );
    }
}
