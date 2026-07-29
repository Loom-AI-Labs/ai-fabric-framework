package ai.fabric.execution.specialist.client;

import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeRequest;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;

public final class DefaultSpecialistClientFactory
    implements SpecialistClientFactory {

    private final SpecialistRegistry specialistRegistry;
    private final AIExecutionGateway executionGateway;
    private final ObjectMapper objectMapper;
    private final SpecialistSchemaBindingValidator bindingValidator;

    public DefaultSpecialistClientFactory(
        SpecialistRegistry specialistRegistry,
        AIExecutionGateway executionGateway,
        ObjectMapper objectMapper
    ) {
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.executionGateway = Objects.requireNonNull(
            executionGateway,
            "executionGateway is required"
        );
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
        this.bindingValidator = new SpecialistSchemaBindingValidator(
            objectMapper
        );
    }

    @Override
    public <I, O> SpecialistClient<I, O> bind(
        SpecialistId specialistId,
        Class<I> inputType,
        Class<O> outputType
    ) {
        Objects.requireNonNull(specialistId, "specialistId is required");
        Objects.requireNonNull(inputType, "inputType is required");
        Objects.requireNonNull(outputType, "outputType is required");
        RegisteredSpecialist registered =
            specialistRegistry.requireRegistered(specialistId);
        if (!(registered.definition().inputAdapter()
                instanceof JsonSchemaSpecialistInputAdapter inputAdapter)) {
            throw new IllegalArgumentException(
                "Typed schema binding requires a manifest JSON input adapter"
            );
        }
        if (!(registered.definition().outputAdapter().outputContract()
                instanceof JsonSchemaOutputContract outputContract)) {
            throw new IllegalArgumentException(
                "Typed schema binding requires a manifest JSON output adapter"
            );
        }
        bindingValidator.validate(
            inputAdapter.schemaDefinition(),
            inputType,
            "input"
        );
        SpecialistSchemaDefinition outputSchema =
            new SpecialistSchemaDefinition(
                "ai.fabric/v1",
                "SpecialistSchema",
                new ai.fabric.execution.specialist.manifest
                    .SpecialistResourceMetadata(
                        outputContract.schemaId().name(),
                        outputContract.schemaId().version()
                    ),
                new SpecialistSchemaSpec(
                    SpecialistSchemaDirection.OUTPUT,
                    "2020-12",
                    outputContract.schema()
                )
            );
        bindingValidator.validate(outputSchema, outputType, "output");
        return new BoundSpecialistClient<>(
            specialistId,
            inputType,
            outputType
        );
    }

    private final class BoundSpecialistClient<I, O>
        implements SpecialistClient<I, O> {

        private final SpecialistId specialistId;
        private final Class<I> inputType;
        private final Class<O> outputType;

        private BoundSpecialistClient(
            SpecialistId specialistId,
            Class<I> inputType,
            Class<O> outputType
        ) {
            this.specialistId = specialistId;
            this.inputType = inputType;
            this.outputType = outputType;
        }

        @Override
        public SpecialistId specialistId() {
            return specialistId;
        }

        @Override
        public AIExecutionResult<O> execute(
            SpecialistInvocation<I> invocation
        ) {
            Objects.requireNonNull(invocation, "invocation is required");
            JsonNode input = objectMapper.valueToTree(
                inputType.cast(invocation.input())
            );
            AIExecutionResult<JsonNode> raw = executionGateway.execute(
                new AIExecutionRequest<>(
                    specialistId,
                    input,
                    invocation.trustedExecutionContext(),
                    invocation.conversationBinding(),
                    invocation.deadline(),
                    invocation.idempotencyKey()
                )
            );
            O output = raw.output() == null
                ? null
                : objectMapper.convertValue(raw.output(), outputType);
            return new AIExecutionResult<>(
                raw.invocationId(),
                raw.specialistId(),
                raw.status(),
                output,
                raw.evidence(),
                raw.diagnostics(),
                raw.failure(),
                raw.startedAt(),
                raw.completedAt(),
                raw.actionProposal(),
                raw.needsUserInput()
            );
        }

        @Override
        public AIExecutionResumeResult<O> resume(
            SpecialistResumeInvocation invocation
        ) {
            Objects.requireNonNull(invocation, "invocation is required");
            AIExecutionResumeResult<JsonNode> raw = executionGateway.resume(
                new AIExecutionResumeRequest(
                    specialistId,
                    invocation.invocationId(),
                    invocation.requestId(),
                    objectMapper.valueToTree(invocation.response()),
                    invocation.trustedExecutionContext(),
                    invocation.idempotencyKey()
                )
            );
            if (raw.executionResult() == null) {
                return new AIExecutionResumeResult<>(
                    raw.status(),
                    null,
                    raw.failure()
                );
            }
            AIExecutionResult<JsonNode> rawExecution =
                raw.executionResult();
            O output = rawExecution.output() == null
                ? null
                : objectMapper.convertValue(
                    rawExecution.output(),
                    outputType
                );
            AIExecutionResult<O> execution = new AIExecutionResult<>(
                rawExecution.invocationId(),
                rawExecution.specialistId(),
                rawExecution.status(),
                output,
                rawExecution.evidence(),
                rawExecution.diagnostics(),
                rawExecution.failure(),
                rawExecution.startedAt(),
                rawExecution.completedAt(),
                rawExecution.actionProposal(),
                rawExecution.needsUserInput()
            );
            return new AIExecutionResumeResult<>(
                raw.status(),
                execution,
                null
            );
        }
    }
}
