package ai.fabric.it;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.service.TestProductService;
import ai.fabric.repository.IndexingQueueRepository;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.indexing.enabled=true",
    "ai.indexing.async-worker.enabled=false",
    "ai.indexing.batch-worker.enabled=false",
    "ai.indexing.cleanup.enabled=false"
})
public class IndexingStrategyIntegrationTest {

    @Autowired
    private TestProductService productService;

    @Autowired
    private TestProductRepository productRepository;

    @Autowired
    private IndexingQueueRepository indexingQueueRepository;

    @MockitoSpyBean
    private VectorManagementService vectorManagementService;

    @BeforeEach
    void setUp() {
        indexingQueueRepository.deleteAll();
        productRepository.deleteAll();
        clearProductVectors();
        reset(vectorManagementService);
    }

    @AfterEach
    void tearDown() {
        indexingQueueRepository.deleteAll();
        productRepository.deleteAll();
        clearProductVectors();
        reset(vectorManagementService);
    }

    @Test
    void createProductEnqueuesAsyncWork() {
        productService.createProduct(buildProduct("Aurora Async Projector"));

        List<IndexingQueueEntry> entries = indexingQueueRepository.findAll();
        assertEquals(1, entries.size(), "ASYNC create should enqueue exactly one entry");

        IndexingQueueEntry entry = entries.get(0);
        assertEquals("test-product", entry.getEntityType());
        assertEquals(AIIndexWorkType.UPSERT, entry.getWorkType());
        assertEquals(AIProcessOperation.CREATE, entry.getSourceOperation());
        assertEquals(IndexingStrategy.ASYNC, entry.getStrategy());
        assertEquals(IndexingStatus.PENDING, entry.getStatus());

        verify(vectorManagementService, never()).storeVector(any(), any(), any(), any(), any());
    }

    @Test
    void deleteProductRespectsEntityLevelSyncOverride() {
        TestProduct persisted = productRepository.save(buildProduct("Compliance Delete Beacon"));

        productService.deleteProduct(persisted.getId());

        assertEquals(1, indexingQueueRepository.count(),
            "SYNC delete should retain one durable completed queue entry");
        IndexingQueueEntry entry = indexingQueueRepository.findAll().getFirst();
        assertEquals(AIIndexWorkType.DELETE, entry.getWorkType());
        assertEquals(IndexingStatus.COMPLETED, entry.getStatus());

        verify(vectorManagementService, atLeastOnce()).removeVector(
            "test-product",
            persisted.getId().toString()
        );
    }

    @Test
    void bulkImportUsesBatchStrategy() {
        List<TestProduct> imports = List.of(
            buildProduct("Bulk Camera One"),
            buildProduct("Bulk Camera Two"),
            buildProduct("Bulk Camera Three")
        );

        productService.bulkImportProducts(imports);

        List<IndexingQueueEntry> entries = indexingQueueRepository.findAll();
        assertEquals(imports.size(), entries.size(), "Each imported product should enqueue a batch entry");
        assertTrue(entries.stream().allMatch(entry -> entry.getStrategy() == IndexingStrategy.BATCH),
            "Bulk import should override strategy to BATCH");
        assertTrue(entries.stream().allMatch(entry ->
                entry.getSourceOperation() == AIProcessOperation.CREATE),
            "Bulk import should enqueue CREATE operations");
    }

    private TestProduct buildProduct(String name) {
        return TestProduct.builder()
            .name(name)
            .description(name + " description")
            .category("category-" + name.replaceAll("\\s+", "-").toLowerCase())
            .brand("BrandX")
            .price(new BigDecimal("199.99"))
            .sku(name.replaceAll("\\s+", "-").toUpperCase())
            .stockQuantity(5)
            .active(true)
            .build();
    }

    private void clearProductVectors() {
        try {
            vectorManagementService.clearVectorsByEntityType("test-product");
        } catch (Exception ignored) {
            // The backing vector store may not be initialised for some profiles; ignore cleanup failures.
        }
    }
}
