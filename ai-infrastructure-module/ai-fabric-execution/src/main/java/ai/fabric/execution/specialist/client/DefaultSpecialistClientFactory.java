package ai.fabric.execution.specialist.client;

import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionResumeRequest;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionSnapshot;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.JsonSchemaSpecialistInputAdapter;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDirection;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class DefaultSpecialistClientFactory
    implements SpecialistClientFactory {

    private final SpecialistRegistry specialistRegistry;
    private final AIExecutionGateway executionGateway;
    private final ObjectMapper objectMapper;
    private final SpecialistSchemaBindingValidator bindingValidator;
    private final ConcurrentMap<BindingKey, SpecialistClient<?, ?>> clients =
        new ConcurrentHashMap<>();

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
        BindingKey key = new BindingKey(
            specialistId,
            inputType,
            outputType
        );
        @SuppressWarnings("unchecked")
        SpecialistClient<I, O> client = (SpecialistClient<I, O>)
            clients.computeIfAbsent(key, this::createBinding);
        return client;
    }

    private SpecialistClient<?, ?> createBinding(BindingKey key) {
        RegisteredSpecialist registered =
            specialistRegistry.requireRegistered(key.specialistId());
        boolean schemaInput = registered.definition().inputAdapter()
            instanceof JsonSchemaSpecialistInputAdapter;
        boolean schemaOutput = registered.definition().outputAdapter()
            .outputContract() instanceof JsonSchemaOutputContract;
        if (schemaInput != schemaOutput) {
            throw new IllegalArgumentException(
                "Typed binding requires matching specialist input and output "
                    + "adapter families"
            );
        }
        if (schemaInput) {
            validateSchemaBinding(registered, key);
        } else {
            validateNativeBinding(registered, key);
        }
        return createBoundClient(
            key.specialistId(),
            key.inputType(),
            key.outputType(),
            schemaInput
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpecialistClient<?, ?> createBoundClient(
        SpecialistId specialistId,
        Class<?> inputType,
        Class<?> outputType,
        boolean schemaBound
    ) {
        return new BoundSpecialistClient(
            specialistId,
            inputType,
            outputType,
            schemaBound
        );
    }

    private void validateSchemaBinding(
        RegisteredSpecialist registered,
        BindingKey key
    ) {
        JsonSchemaSpecialistInputAdapter inputAdapter =
            (JsonSchemaSpecialistInputAdapter)
                registered.definition().inputAdapter();
        JsonSchemaOutputContract outputContract =
            (JsonSchemaOutputContract) registered.definition()
                .outputAdapter()
                .outputContract();
        bindingValidator.validate(
            inputAdapter.schemaDefinition(),
            key.inputType(),
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
        bindingValidator.validate(
            outputSchema,
            key.outputType(),
            "output"
        );
    }

    private void validateNativeBinding(
        RegisteredSpecialist registered,
        BindingKey key
    ) {
        Class<?> registeredInput = registered.definition()
            .inputAdapter()
            .inputType();
        Class<?> registeredOutput = registered.definition()
            .outputAdapter()
            .outputType();
        if (!registeredInput.equals(key.inputType())) {
            throw new IllegalArgumentException(
                "Typed input binding must use "
                    + registeredInput.getName()
                    + " but was "
                    + key.inputType().getName()
            );
        }
        if (!registeredOutput.equals(key.outputType())) {
            throw new IllegalArgumentException(
                "Typed output binding must use "
                    + registeredOutput.getName()
                    + " but was "
                    + key.outputType().getName()
            );
        }
    }

    private final class BoundSpecialistClient<I, O>
        implements SpecialistClient<I, O> {

        private final SpecialistId specialistId;
        private final Class<I> inputType;
        private final Class<O> outputType;
        private final boolean schemaBound;

        private BoundSpecialistClient(
            SpecialistId specialistId,
            Class<I> inputType,
            Class<O> outputType,
            boolean schemaBound
        ) {
            this.specialistId = specialistId;
            this.inputType = inputType;
            this.outputType = outputType;
            this.schemaBound = schemaBound;
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
            AIExecutionResult<?> raw = executionGateway.execute(
                request(invocation)
            );
            return convertExecution(raw);
        }

        @Override
        public ExecutionHandle submit(
            SpecialistInvocation<I> invocation
        ) {
            Objects.requireNonNull(invocation, "invocation is required");
            return executionGateway.submit(request(invocation));
        }

        @Override
        public Optional<SpecialistExecutionSnapshot<O>> find(
            String invocationId,
            ai.fabric.execution.context.TrustedExecutionContext
                trustedExecutionContext
        ) {
            return executionGateway.find(
                invocationId,
                trustedExecutionContext
            ).map(this::convertSnapshot);
        }

        @Override
        public boolean cancel(
            String invocationId,
            ai.fabric.execution.context.TrustedExecutionContext
                trustedExecutionContext
        ) {
            return executionGateway.cancel(
                invocationId,
                trustedExecutionContext
            );
        }

        @Override
        public AIExecutionResumeResult<O> resume(
            SpecialistResumeInvocation invocation
        ) {
            Objects.requireNonNull(invocation, "invocation is required");
            AIExecutionResumeResult<?> raw = executionGateway.resume(
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
            AIExecutionResult<O> execution = convertExecution(
                raw.executionResult()
            );
            return new AIExecutionResumeResult<>(
                raw.status(),
                execution,
                null
            );
        }

        private AIExecutionRequest<Object> request(
            SpecialistInvocation<I> invocation
        ) {
            Object input = schemaBound
                ? objectMapper.valueToTree(
                    inputType.cast(invocation.input())
                )
                : inputType.cast(invocation.input());
            return new AIExecutionRequest<>(
                specialistId,
                input,
                invocation.trustedExecutionContext(),
                invocation.conversationBinding(),
                invocation.deadline(),
                invocation.idempotencyKey()
            );
        }

        private SpecialistExecutionSnapshot<O> convertSnapshot(
            ExecutionSnapshot snapshot
        ) {
            AIExecutionResult<O> result = snapshot.result() == null
                ? null
                : convertExecution(snapshot.result());
            return new SpecialistExecutionSnapshot<>(
                snapshot.handle(),
                result
            );
        }

        private AIExecutionResult<O> convertExecution(
            AIExecutionResult<?> raw
        ) {
            O output = convertOutput(raw.output());
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

        private O convertOutput(Object output) {
            if (output == null) {
                return null;
            }
            if (outputType.isInstance(output)) {
                return outputType.cast(output);
            }
            if (schemaBound) {
                return objectMapper.convertValue(output, outputType);
            }
            throw new IllegalStateException(
                "Specialist returned an output outside its native binding"
            );
        }
    }

    private record BindingKey(
        SpecialistId specialistId,
        Class<?> inputType,
        Class<?> outputType
    ) {}
}
