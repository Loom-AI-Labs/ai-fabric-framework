package ai.fabric.it.service;

import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Test-facing service exposing methods annotated with {@link AIProcess} so that
 * integration scenarios can exercise the method-level AI processing aspect.
 */
@Service
@RequiredArgsConstructor
public class TestProductService {

    private final TestProductRepository productRepository;

    @AIProcess(entityType = "test-product", operation = AIProcessOperation.CREATE)
    @Transactional
    public TestProduct createProduct(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(operation = AIProcessOperation.CREATE)
    @Transactional
    public TestProduct createProductImplicit(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "test-product", operation = AIProcessOperation.UPDATE)
    @Transactional
    public TestProduct updateProduct(Long id, String name, String description, BigDecimal price) {
        TestProduct existing = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));

        if (name != null) {
            existing.setName(name);
        }
        if (description != null) {
            existing.setDescription(description);
        }
        if (price != null) {
            existing.setPrice(price);
        }

        return productRepository.save(existing);
    }

    @AIProcess(entityType = "test-product", operation = AIProcessOperation.DELETE)
    @Transactional
    public TestProduct deleteProduct(Long id) {
        TestProduct existing = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
        productRepository.delete(existing);
        return existing;
    }

    @Transactional
    public TestProduct createProductWithoutEmbedding(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(
        entityType = "test-product",
        operation = AIProcessOperation.CREATE,
        indexingStrategy = IndexingStrategy.BATCH
    )
    @Transactional
    public List<TestProduct> bulkImportProducts(List<TestProduct> products) {
        return productRepository.saveAll(products);
    }

    @Transactional
    public TestProduct createProductWithoutIndexing(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "test-product", operation = AIProcessOperation.CREATE)
    @Transactional
    public TestProduct createProductWithAnalysis(TestProduct product) {
        return productRepository.save(product);
    }

    public List<TestProduct> searchProducts(String query) {
        return productRepository.findByNameContainingIgnoreCase(query);
    }

    public TestProduct analyzeProduct(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }

    public TestProduct getProduct(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }
}
