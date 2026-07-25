package ai.fabric.indexing.descriptor;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AIContextDestination;
import ai.fabric.indexing.api.AISearchDestination;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.api.EntityIdentityResolver;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Id;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIEntityDescriptorRegistryTest {

    @Test
    void compilesStableTypedDescriptorAndInheritedIdentity() {
        AIEntityDescriptorRegistry registry = registry(new MockEnvironment(), null);

        var descriptor = registry.resolve(CatalogProduct.class);

        assertThat(descriptor.entityType()).isEqualTo("catalog-product");
        assertThat(descriptor.defaultStrategy()).isEqualTo(IndexingStrategy.ASYNC);
        assertThat(descriptor.identitySource()).isEqualTo("@AIIdentity:id");
        assertThat(descriptor.searchableFields())
            .extracting(field -> field.name())
            .containsExactly("title", "details");
        assertThat(descriptor.effectiveCapabilities())
            .contains(
                ai.fabric.indexing.model.AIEntityCapability.INDEXING,
                ai.fabric.indexing.model.AIEntityCapability.SEMANTIC_SEARCH,
                ai.fabric.indexing.model.AIEntityCapability.RAG_CONTEXT,
                ai.fabric.indexing.model.AIEntityCapability.VECTOR_METADATA
            );
        assertThat(descriptor.projectionHash()).hasSize(64);
        assertThat(descriptor.projectionHash())
            .isEqualTo(registry.resolve(CatalogProduct.class).projectionHash());
        assertThat(descriptor.identityResolver().resolveIdentity(
            new CatalogProduct("p-1", "Laptop", "Quiet", "tenant-a")
        )).isEqualTo("p-1");
    }

    @Test
    void configCanNarrowDestinationsWithoutReplacingAbsentAnnotationValues() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "ai-entities.catalog-product.searchable-fields[0].name",
                "details"
            )
            .withProperty(
                "ai-entities.catalog-product.searchable-fields[0].destinations[0]",
                "RAG_CONTEXT"
            );

        var descriptor = registry(environment, null).resolve(CatalogProduct.class);
        var details = descriptor.searchableFields().stream()
            .filter(field -> field.name().equals("details"))
            .findFirst()
            .orElseThrow();

        assertThat(details.destinations()).containsExactly(AISearchDestination.RAG_CONTEXT);
        assertThat(details.preprocessing()).isEqualTo(AISearchPreprocessing.CLEAN);
        assertThat(details.maxLength()).isEqualTo(120);
        assertThat(details.priority()).isEqualTo(20);
    }

    @Test
    void rejectsConfigThatWidensAnAnnotationBoundary() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "ai-entities.rag-only.searchable-fields[0].name",
                "text"
            )
            .withProperty(
                "ai-entities.rag-only.searchable-fields[0].destinations[0]",
                "SEMANTIC_SEARCH"
            );

        assertThatThrownBy(() -> registry(environment, null).resolve(RagOnlyEntity.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("cannot widen");
    }

    @Test
    void configCanRefineAutoContextTypeButCannotReplaceConcreteType() {
        MockEnvironment autoEnvironment = new MockEnvironment()
            .withProperty(
                "ai-entities.auto-context.metadata-fields[0].name",
                "kind"
            )
            .withProperty(
                "ai-entities.auto-context.metadata-fields[0].data-type",
                "STRING"
            );

        var descriptor = registry(autoEnvironment, null).resolve(AutoContextEntity.class);

        assertThat(descriptor.contextFields().getFirst().dataType())
            .isEqualTo(AIContextDataType.STRING);

        MockEnvironment concreteEnvironment = new MockEnvironment()
            .withProperty(
                "ai-entities.concrete-context.metadata-fields[0].name",
                "amount"
            )
            .withProperty(
                "ai-entities.concrete-context.metadata-fields[0].data-type",
                "STRING"
            );
        assertThatThrownBy(() -> registry(concreteEnvironment, null)
            .resolve(ConcreteContextEntity.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("cannot replace annotated dataType");
    }

    @Test
    void failsStartupWhenSanitizationHasNoPiiProvider() {
        assertThatThrownBy(() -> registry(new MockEnvironment(), null)
            .resolve(SanitizedEntity.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("PIIDetectionService");
    }

    @Test
    void rejectsAmbiguousIdentity() {
        assertThatThrownBy(() -> registry(new MockEnvironment(), null)
            .resolve(AmbiguousIdentityEntity.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("Multiple @AIIdentity");
    }

    @Test
    void resolvesGetterJpaIdentity() {
        AIEntityDescriptorRegistry registry = registry(new MockEnvironment(), null);

        var descriptor = registry.resolve(GetterIdentityEntity.class);

        assertThat(descriptor.identitySource()).isEqualTo("JPA:id");
        assertThat(descriptor.identityResolver().resolveIdentity(
            new GetterIdentityEntity("getter-1")
        )).isEqualTo("getter-1");
    }

    @Test
    void canonicalizesEmbeddedJpaIdentityWithStablePropertyOrdering() {
        AIEntityDescriptorRegistry registry = registry(new MockEnvironment(), null);
        var descriptor = registry.resolve(EmbeddedIdentityEntity.class);

        Object identity = descriptor.identityResolver().resolveIdentity(
            new EmbeddedIdentityEntity(new CompositeIdentity("tenant-a", 17L))
        );

        assertThat(descriptor.identitySource()).isEqualTo("JPA:id");
        assertThat(identity).isEqualTo("{\"number\":17,\"tenant\":\"tenant-a\"}");
    }

    @Test
    void applicationIdentityResolverSupportsNonJpaDomainIdentity() {
        EntityIdentityResolver resolver = new EntityIdentityResolver() {
            @Override
            public boolean supports(Class<?> entityClass) {
                return entityClass == CustomIdentityEntity.class;
            }

            @Override
            public Object resolveIdentity(Object entity) {
                CustomIdentityEntity value = (CustomIdentityEntity) entity;
                return new CompositeIdentity(value.workspace, value.number);
            }

            @Override
            public String source() {
                return "application:test-custom";
            }
        };
        AIEntityDescriptorRegistry registry = registry(
            new MockEnvironment(),
            null,
            List.of(resolver)
        );

        var descriptor = registry.resolve(CustomIdentityEntity.class);

        assertThat(descriptor.identitySource()).isEqualTo("application:test-custom");
        assertThat(descriptor.identityResolver().resolveIdentity(
            new CustomIdentityEntity("workspace-b", 4L)
        )).isEqualTo("{\"number\":4,\"tenant\":\"workspace-b\"}");
    }

    @Test
    void rejectsBlankAndDuplicateEntityTypes() {
        AIEntityDescriptorRegistry registry = registry(new MockEnvironment(), null);

        assertThatThrownBy(() -> registry.resolve(BlankEntityType.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("entityType");

        registry.resolve(FirstDuplicateEntity.class);
        assertThatThrownBy(() -> registry.resolve(SecondDuplicateEntity.class))
            .isInstanceOf(AIEntityContractException.class)
            .hasMessageContaining("Duplicate entityType");
    }

    @Test
    void compilesOperationStrategiesAndMigrationRepositoryIntoTheDescriptor() {
        var descriptor = registry(new MockEnvironment(), null)
            .resolve(StrategyEntity.class);

        assertThat(descriptor.defaultStrategy())
            .isEqualTo(IndexingStrategy.BATCH);
        assertThat(descriptor.strategyFor(
            ai.fabric.indexing.api.AIProcessOperation.CREATE
        )).isEqualTo(IndexingStrategy.SYNC);
        assertThat(descriptor.strategyFor(
            ai.fabric.indexing.api.AIProcessOperation.UPDATE
        )).isEqualTo(IndexingStrategy.BATCH);
        assertThat(descriptor.strategyFor(
            ai.fabric.indexing.api.AIProcessOperation.DELETE
        )).isEqualTo(IndexingStrategy.ASYNC);
        assertThat(descriptor.migrationRepository())
            .isEqualTo(StrategyEntityRepository.class);
    }

    private AIEntityDescriptorRegistry registry(
        MockEnvironment environment,
        PIIDetectionService piiService
    ) {
        return registry(environment, piiService, List.of());
    }

    private AIEntityDescriptorRegistry registry(
        MockEnvironment environment,
        PIIDetectionService piiService,
        List<EntityIdentityResolver> identityResolvers
    ) {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(environment);
        loader.loadConfiguration();
        return new AIEntityDescriptorRegistry(
            loader,
            identityResolvers,
            List.of(),
            provider(PIIDetectionService.class, piiService),
            new ObjectMapper()
        );
    }

    private <T> ObjectProvider<T> provider(Class<T> type, T value) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (value != null) {
            factory.addBean("value", value);
        }
        return factory.getBeanProvider(type);
    }

    static class IdentityBase {
        @Id
        @AIIdentity
        String id;

        IdentityBase(String id) {
            this.id = id;
        }
    }

    @AICapable(entityType = "catalog-product")
    static class CatalogProduct extends IdentityBase {
        @AISearchable(name = "title", priority = 90, required = true)
        String title;

        @AISearchable(
            name = "details",
            preprocessing = AISearchPreprocessing.CLEAN,
            maxLength = 120,
            priority = 20
        )
        String details;

        @AIContext(
            key = "tenantId",
            dataType = AIContextDataType.ID,
            destinations = {AIContextDestination.VECTOR_METADATA},
            required = true
        )
        String tenantId;

        CatalogProduct(String id, String title, String details, String tenantId) {
            super(id);
            this.title = title;
            this.details = details;
            this.tenantId = tenantId;
        }
    }

    @AICapable(entityType = "rag-only", indexingStrategy = IndexingStrategy.SYNC)
    static class RagOnlyEntity {
        @AIIdentity
        String id = "r-1";

        @AISearchable(destinations = {AISearchDestination.RAG_CONTEXT})
        String text = "Evidence";
    }

    @AICapable(entityType = "sanitized")
    static class SanitizedEntity {
        @AIIdentity
        String id = "s-1";

        @AISearchable(preprocessing = AISearchPreprocessing.SANITIZE)
        String text = "private";
    }

    @AICapable(entityType = "ambiguous")
    static class AmbiguousIdentityEntity {
        @AIIdentity
        String first = "one";

        @AIIdentity
        String second = "two";

        @AISearchable
        String text = "value";
    }

    @AICapable(entityType = "getter-identity")
    static class GetterIdentityEntity {
        private final String id;

        GetterIdentityEntity(String id) {
            this.id = id;
        }

        @Id
        String getId() {
            return id;
        }

        @AISearchable
        String getText() {
            return "getter";
        }
    }

    record CompositeIdentity(String tenant, long number) {
    }

    @AICapable(entityType = "embedded-identity")
    static class EmbeddedIdentityEntity {
        @EmbeddedId
        CompositeIdentity id;

        @AISearchable
        String text = "embedded";

        EmbeddedIdentityEntity(CompositeIdentity id) {
            this.id = id;
        }
    }

    @AICapable(entityType = "custom-identity")
    static class CustomIdentityEntity {
        final String workspace;
        final long number;

        @AISearchable
        String text = "custom";

        CustomIdentityEntity(String workspace, long number) {
            this.workspace = workspace;
            this.number = number;
        }
    }

    @AICapable(entityType = " ")
    static class BlankEntityType {
        @AIIdentity
        String id = "blank";

        @AISearchable
        String text = "blank";
    }

    @AICapable(entityType = "duplicate")
    static class FirstDuplicateEntity {
        @AIIdentity
        String id = "one";

        @AISearchable
        String text = "first";
    }

    @AICapable(entityType = "duplicate")
    static class SecondDuplicateEntity {
        @AIIdentity
        String id = "two";

        @AISearchable
        String text = "second";
    }

    @AICapable(entityType = "auto-context")
    static class AutoContextEntity {
        @AIIdentity
        String id = "auto";

        @AISearchable
        String text = "auto";

        @AIContext(key = "kind")
        String kind = "catalog";
    }

    @AICapable(entityType = "concrete-context")
    static class ConcreteContextEntity {
        @AIIdentity
        String id = "concrete";

        @AISearchable
        String text = "concrete";

        @AIContext(key = "amount", dataType = AIContextDataType.NUMBER)
        Integer amount = 4;
    }

    @AICapable(
        entityType = "strategy-entity",
        indexingStrategy = IndexingStrategy.BATCH,
        onCreateStrategy = IndexingStrategy.SYNC,
        onUpdateStrategy = IndexingStrategy.AUTO,
        onDeleteStrategy = IndexingStrategy.ASYNC,
        migrationRepository = StrategyEntityRepository.class
    )
    static class StrategyEntity {
        @AIIdentity
        String id = "strategy";

        @AISearchable
        String text = "strategy";
    }

    interface StrategyEntityRepository
        extends JpaRepository<StrategyEntity, String> {
    }
}
