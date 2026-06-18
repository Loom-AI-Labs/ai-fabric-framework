package ai.fabric.it.service;

import ai.fabric.annotation.AIProcess;
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

    @AIProcess(entityType = "product", processType = "create")
    @Transactional
    public TestProduct createProduct(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(processType = "create")
    @Transactional
    public TestProduct createProductImplicit(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "product", processType = "update")
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

    @AIProcess(entityType = "product", processType = "delete", generateEmbedding = false, indexForSearch = false)
    @Transactional
    public TestProduct deleteProduct(Long id) {
        TestProduct existing = productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
        productRepository.delete(existing);
        return existing;
    }

    @AIProcess(entityType = "product", processType = "create", generateEmbedding = false, indexForSearch = false)
    @Transactional
    public TestProduct createProductWithoutEmbedding(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "product", processType = "create", indexingStrategy = IndexingStrategy.BATCH)
    @Transactional
    public List<TestProduct> bulkImportProducts(List<TestProduct> products) {
        return productRepository.saveAll(products);
    }

    @AIProcess(entityType = "product", processType = "create", indexForSearch = false)
    @Transactional
    public TestProduct createProductWithoutIndexing(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "product", processType = "create", enableAnalysis = true)
    @Transactional
    public TestProduct createProductWithAnalysis(TestProduct product) {
        return productRepository.save(product);
    }

    @AIProcess(entityType = "product", processType = "search", generateEmbedding = false, indexForSearch = false)
    public List<TestProduct> searchProducts(String query) {
        return productRepository.findByNameContainingIgnoreCase(query);
    }

    @AIProcess(entityType = "product", processType = "analyze", enableAnalysis = true)
    @Transactional
    public TestProduct analyzeProduct(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }

    public TestProduct getProduct(Long id) {
        return productRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Product not found: " + id));
    }
}
