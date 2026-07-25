package ai.fabric.vector.contract;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.vector.lucene.LuceneVectorDatabaseService;
import ai.fabric.vector.memory.InMemoryVectorDatabaseService;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import static ai.fabric.vector.contract.VectorDatabaseServiceContractAssertions.STANDARD_EFFICIENT_COUNT;
import static org.junit.jupiter.api.DynamicContainer.dynamicContainer;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class VectorDatabaseServiceContractTest {

    private static final VectorDatabaseServiceContractAssertions CONTRACT =
        new VectorDatabaseServiceContractAssertions();

    @TempDir
    Path tempDir;

    @TestFactory
    Stream<DynamicNode> localProviderContracts() {
        return providerSpecs().map(spec -> dynamicContainer(spec.name(), Stream.of(
            dynamicTest("declares precise lifecycle capabilities", () ->
                withService(spec, service -> CONTRACT.assertPreciseCapabilities(service, spec.capabilities()))),
            dynamicTest("stores fetches searches updates and removes records", () ->
                withService(spec, CONTRACT::assertStoreSearchUpdateAndRemove)),
            dynamicTest("scans with metadata filters cursors and projection flags", () ->
                withService(spec, CONTRACT::assertMetadataScanAndProjection)),
            dynamicTest("does not coerce decimal metadata into integral filters", () ->
                withService(spec, CONTRACT::assertIntegralMetadataFilterDoesNotMatchDecimalMetadata)),
            dynamicTest("applies exact metadata filters before search and scan limits", () ->
                withService(spec, CONTRACT::assertExactMetadataFilteringPrecedesResultLimits)),
            dynamicTest("preserves empty string metadata filters as exact values", () ->
                withService(spec, CONTRACT::assertEmptyStringMetadataFilterIsExact)),
            dynamicTest("rejects invalid direct write inputs and no-ops invalid identity lookups", () ->
                withService(spec, CONTRACT::assertInvalidDirectInputContract)),
            dynamicTest("batch operations and clear by entity type are isolated", () ->
                withService(spec, CONTRACT::assertBatchAndClearContract)),
            dynamicTest("isolates records across configured provider scopes", () ->
                withServicePair(spec, CONTRACT::assertProviderScopeIsolation))
        )));
    }

    private Stream<ProviderSpec> providerSpecs() {
        return Stream.of(
            new ProviderSpec(
                "memory",
                ignored -> new InMemoryVectorDatabaseService(new AIProviderConfig()),
                STANDARD_EFFICIENT_COUNT,
                service -> {
                }
            ),
            new ProviderSpec(
                "lucene",
                this::createLuceneService,
                STANDARD_EFFICIENT_COUNT,
                service -> ((LuceneVectorDatabaseService) service).cleanup()
            )
        );
    }

    private void withService(ProviderSpec spec, CheckedConsumer<VectorDatabaseService> assertion) throws Exception {
        VectorDatabaseService service = spec.factory().apply(tempDir);
        try {
            assertion.accept(service);
        } finally {
            spec.cleanup().accept(service);
        }
    }

    private void withServicePair(ProviderSpec spec, CheckedBiConsumer<VectorDatabaseService, VectorDatabaseService> assertion)
        throws Exception {
        VectorDatabaseService left = spec.factory().apply(tempDir);
        VectorDatabaseService right = spec.factory().apply(tempDir);
        try {
            assertion.accept(left, right);
        } finally {
            spec.cleanup().accept(left);
            spec.cleanup().accept(right);
        }
    }

    private LuceneVectorDatabaseService createLuceneService(Path baseDir) {
        LuceneVectorDatabaseService service = new LuceneVectorDatabaseService(new AIProviderConfig());
        setField(service, "indexPath", baseDir.resolve(UUID.randomUUID().toString()).toString());
        setField(service, "similarityThreshold", 0.0d);
        setField(service, "maxResults", 50);
        setField(service, "cleanupOnClose", true);
        service.initialize();
        return service;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set field " + fieldName, e);
        }
    }

    private record ProviderSpec(
        String name,
        Function<Path, VectorDatabaseService> factory,
        VectorDatabaseServiceContractAssertions.CapabilityExpectations capabilities,
        Consumer<VectorDatabaseService> cleanup
    ) {
    }

    @FunctionalInterface
    private interface CheckedConsumer<T> {
        void accept(T value) throws Exception;
    }

    @FunctionalInterface
    private interface CheckedBiConsumer<T, U> {
        void accept(T left, U right) throws Exception;
    }
}
