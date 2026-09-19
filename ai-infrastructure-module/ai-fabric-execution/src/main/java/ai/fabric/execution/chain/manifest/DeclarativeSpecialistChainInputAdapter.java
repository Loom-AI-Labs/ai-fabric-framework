package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainInputAdapter;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DeclarativeSpecialistChainInputAdapter
    implements SpecialistChainInputAdapter<JsonNode> {

    private final SpecialistChainComponentId id;
    private final SpecialistSchemaDefinition schema;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final BoundedJsonSupport json;
    private final String messagePointer;
    private final List<SpecialistChainManifest.ContextValue> contextValues;

    DeclarativeSpecialistChainInputAdapter(
        SpecialistChainComponentId id,
        SpecialistSchemaDefinition schema,
        SpecialistJsonSchemaValidator schemaValidator,
        BoundedJsonSupport json,
        String messagePointer,
        List<SpecialistChainManifest.ContextValue> contextValues
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.schema = Objects.requireNonNull(schema, "schema is required");
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.json = Objects.requireNonNull(json, "json is required");
        this.messagePointer = json.validatePointer(
            messagePointer,
            "managerMessagePointer"
        );
        this.contextValues = contextValues == null
            ? List.of()
            : List.copyOf(contextValues);
    }

    @Override
    public SpecialistChainComponentId id() {
        return id;
    }

    @Override
    public Class<JsonNode> inputType() {
        return JsonNode.class;
    }

    @Override
    public String currentUserMessage(JsonNode input) {
        validate(input);
        JsonNode selected = json.select(
            input,
            messagePointer,
            true,
            "managerMessagePointer"
        );
        if (!selected.isTextual()) {
            throw new IllegalArgumentException(
                "managerMessagePointer must select a string"
            );
        }
        String message = selected.textValue().trim();
        if (message.isEmpty()
            || message.length()
                > SpecialistChainManagerInput.MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                "managerMessagePointer must select a bounded non-blank string"
            );
        }
        return message;
    }

    @Override
    public List<ConversationManagerContextValue> applicationContext(
        JsonNode input
    ) {
        validate(input);
        List<ConversationManagerContextValue> result = new ArrayList<>();
        BoundedJsonSupport.WorkBudget workBudget = json.newWorkBudget();
        for (SpecialistChainManifest.ContextValue value : contextValues) {
            JsonNode selected = json.select(
                input,
                value.valuePointer(),
                value.requiredOrDefault(),
                "managerContext.valuePointer",
                workBudget
            );
            if (selected == null) {
                continue;
            }
            if (!selected.isTextual()) {
                throw new IllegalArgumentException(
                    "Manager context pointers must select strings"
                );
            }
            result.add(new ConversationManagerContextValue(
                value.name(),
                selected.textValue()
            ));
        }
        return List.copyOf(result);
    }

    private void validate(JsonNode input) {
        Objects.requireNonNull(input, "chain input is required");
        schemaValidator.validate(schema, input);
    }
}
