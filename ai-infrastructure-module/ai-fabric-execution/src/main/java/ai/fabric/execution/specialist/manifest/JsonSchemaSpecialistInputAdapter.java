package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.specialist.SpecialistInputAdapter;
import ai.fabric.execution.specialist.SpecialistLimits;
import ai.fabric.intent.orchestration.OrchestrationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;

public final class JsonSchemaSpecialistInputAdapter
    implements SpecialistInputAdapter<JsonNode> {

    private final SpecialistSchemaDefinition schema;
    private final SpecialistInputSpec specification;
    private final SpecialistConversationSpec conversation;
    private final SpecialistLimits limits;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final CanonicalJsonSupport canonicalJson;
    private final ObjectMapper objectMapper;

    public JsonSchemaSpecialistInputAdapter(
        SpecialistSchemaDefinition schema,
        SpecialistInputSpec specification,
        SpecialistConversationSpec conversation,
        SpecialistLimits limits,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        ObjectMapper objectMapper
    ) {
        this.schema = Objects.requireNonNull(schema, "schema is required");
        this.specification = Objects.requireNonNull(
            specification,
            "specification is required"
        );
        this.conversation = Objects.requireNonNull(
            conversation,
            "conversation is required"
        );
        this.limits = Objects.requireNonNull(limits, "limits is required");
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
    }

    @Override
    public Class<JsonNode> inputType() {
        return JsonNode.class;
    }

    public SpecialistSchemaDefinition schemaDefinition() {
        return schema;
    }

    @Override
    public void validate(JsonNode input) {
        schemaValidator.validate(schema, input);
        requireTextPointer(
            input,
            specification.primaryTextPointer(),
            "primaryTextPointer"
        );
        if (specification.conversationTextPointer() != null) {
            requireTextPointer(
                input,
                specification.conversationTextPointer(),
                "conversationTextPointer"
            );
        }
        for (String pointer : specification.contextPointers()) {
            JsonNode selected = select(input, pointer, "contextPointers");
            if (selected.isMissingNode()) {
                throw new IllegalArgumentException(
                    "A configured context pointer does not exist in the input"
                );
            }
        }
        if (canonicalJson.write(input).length() > limits.maxInputCharacters()) {
            throw new IllegalArgumentException(
                "Canonical input exceeds specialist limit"
            );
        }
    }

    @Override
    public String renderModelInput(JsonNode input) {
        String primary = requireTextPointer(
            input,
            specification.primaryTextPointer(),
            "primaryTextPointer"
        );
        if (specification.contextPointers().isEmpty()) {
            return "Application question:\n" + primary;
        }
        ObjectNode context = objectMapper.createObjectNode();
        for (String pointer : specification.contextPointers()) {
            context.set(pointer, select(input, pointer, "contextPointers"));
        }
        return """
            Application question:
            %s

            Untrusted application JSON context:
            %s
            """.formatted(primary, canonicalJson.write(context)).trim();
    }

    @Override
    public String conversationInput(JsonNode input) {
        if (specification.conversationTextPointer() == null) {
            return null;
        }
        return requireTextPointer(
            input,
            specification.conversationTextPointer(),
            "conversationTextPointer"
        );
    }

    @Override
    public OrchestrationContext orchestrationContext(JsonNode input) {
        var builder = OrchestrationContext.builder();
        if (specification.context() != null
            && specification.context().position() != null
            && !specification.context().position().isBlank()) {
            builder.position(specification.context().position().trim());
        }
        return builder.build();
    }

    @Override
    public SpecialistConversationBinding conversationBinding() {
        return conversation.binding();
    }

    @Override
    public boolean recordValidatedTurns() {
        return conversation.recordValidatedTurns();
    }

    private String requireTextPointer(
        JsonNode input,
        String pointer,
        String field
    ) {
        JsonNode selected = select(input, pointer, field);
        if (!selected.isTextual() || selected.textValue().isBlank()) {
            throw new IllegalArgumentException(
                field + " must select a non-blank string"
            );
        }
        return selected.textValue().trim();
    }

    private JsonNode select(JsonNode input, String pointer, String field) {
        if (pointer == null || !pointer.startsWith("/")) {
            throw new IllegalArgumentException(
                field + " must contain RFC 6901 JSON pointers"
            );
        }
        try {
            return input.at(pointer);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                field + " contains an invalid RFC 6901 JSON pointer",
                ex
            );
        }
    }
}
