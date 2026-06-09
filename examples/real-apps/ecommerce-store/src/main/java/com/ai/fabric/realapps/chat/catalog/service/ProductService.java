package com.ai.fabric.realapps.chat.catalog.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.indexing.events.ProductDeleteIndexingEvent;
import com.ai.fabric.realapps.chat.indexing.events.ProductUpsertIndexingEvent;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    @Transactional(readOnly = true)
    public List<Product> list(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return productRepository.findAll().stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public Product get(long id) {
        return productRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    @Transactional(readOnly = true)
    public Optional<Product> findBySku(String sku) {
        String normalized = SkuNormalizer.normalizeForLookup(sku);
        if (!StringUtils.hasText(normalized)) {
            return Optional.empty();
        }

        // Try exact match first (fast path), then case-insensitive.
        return productRepository.findBySku(normalized)
            .or(() -> productRepository.findBySkuIgnoreCase(normalized));
    }

    @Transactional
    public Product createProduct(String sku,
                                 String name,
                                 String description,
                                 String category,
                                 String tags,
                                 String imageUrl,
                                 BigDecimal price,
                                 String currency,
                                 Integer inStockQty) {
        if (!StringUtils.hasText(sku)) {
            throw new IllegalArgumentException("sku is required");
        }
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("name is required");
        }
        if (!StringUtils.hasText(description)) {
            throw new IllegalArgumentException("description is required");
        }

        String normalizedSku = SkuNormalizer.normalize(sku);
        if (!StringUtils.hasText(normalizedSku)) {
            throw new IllegalArgumentException("sku is required");
        }
        if (productRepository.existsBySkuIgnoreCase(normalizedSku)) {
            throw new IllegalArgumentException("sku already exists: " + normalizedSku);
        }

        Product product = new Product();
        product.setSku(normalizedSku);
        product.setName(name.trim());
        product.setDescription(description.trim());
        product.setCategory(StringUtils.hasText(category) ? category.trim() : null);
        product.setTags(StringUtils.hasText(tags) ? tags.trim() : null);
        product.setImageUrl(StringUtils.hasText(imageUrl) ? imageUrl.trim() : null);
        product.setPrice(price);
        product.setCurrency(StringUtils.hasText(currency) ? currency.trim().toUpperCase() : "USD");
        product.setInStockQty(inStockQty != null ? Math.max(0, inStockQty) : 0);

        Product saved = productRepository.save(product);
        publishUpsert(saved);
        return saved;
    }

    @Transactional
    public Product updateProduct(long id,
                                 String sku,
                                 String name,
                                 String description,
                                 String category,
                                 String tags,
                                 String imageUrl,
                                 BigDecimal price,
                                 String currency,
                                 Integer inStockQty) {
        Product product = get(id);
        String skuBefore = product.getSku();

        if (StringUtils.hasText(sku)) {
            String normalizedSku = SkuNormalizer.normalize(sku);
            if (!StringUtils.hasText(normalizedSku)) {
                throw new IllegalArgumentException("sku is required");
            }
            if (!equalsIgnoreCase(normalizedSku, product.getSku()) && productRepository.existsBySkuIgnoreCase(normalizedSku)) {
                throw new IllegalArgumentException("sku already exists: " + normalizedSku);
            }
            product.setSku(normalizedSku);
        }
        if (StringUtils.hasText(name)) {
            product.setName(name.trim());
        }
        if (StringUtils.hasText(description)) {
            product.setDescription(description.trim());
        }
        if (category != null) {
            product.setCategory(StringUtils.hasText(category) ? category.trim() : null);
        }
        if (tags != null) {
            product.setTags(StringUtils.hasText(tags) ? tags.trim() : null);
        }
        if (imageUrl != null) {
            product.setImageUrl(StringUtils.hasText(imageUrl) ? imageUrl.trim() : null);
        }
        if (price != null) {
            product.setPrice(price);
        }
        if (StringUtils.hasText(currency)) {
            product.setCurrency(currency.trim().toUpperCase());
        }
        if (inStockQty != null) {
            product.setInStockQty(Math.max(0, inStockQty));
        }

        Product saved = productRepository.save(product);
        if (skuBefore != null && saved.getSku() != null && !skuBefore.equalsIgnoreCase(saved.getSku())) {
            eventPublisher.publishEvent(new ProductDeleteIndexingEvent(skuBefore));
        }
        publishUpsert(saved);
        return saved;
    }

    @Transactional
    public Product updateProductStock(String sku, int newInStockQty) {
        String normalizedSku = SkuNormalizer.normalizeForLookup(sku);
        if (!StringUtils.hasText(normalizedSku)) {
            throw new IllegalArgumentException("sku is required");
        }
        Product product = findBySku(normalizedSku)
            .orElseThrow(() -> new EntityNotFoundException("Product not found for sku: " + normalizedSku));
        product.setInStockQty(Math.max(0, newInStockQty));
        Product saved = productRepository.save(product);
        publishUpsert(saved);
        return saved;
    }

    @Transactional
    public Product deleteProduct(long id) {
        Product product = get(id);
        productRepository.delete(product);
        if (product.getSku() != null) {
            eventPublisher.publishEvent(new ProductDeleteIndexingEvent(product.getSku()));
        }
        return product;
    }

    @Transactional(readOnly = true)
    public long count() {
        return productRepository.count();
    }

    /**
     * Deterministic keyword search that ranks matches using product name + category only.
     *
     * <p>This is intended for demos where embedding providers may be disabled.</p>
     */
    @Transactional(readOnly = true)
    public List<Product> searchByNameAndCategory(String query, int limit) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        String normalized = normalizeQuery(query);
        if (!StringUtils.hasText(normalized)) {
            return List.of();
        }
        List<String> tokens = tokenize(normalized);

        record Scored(Product product, int score) {
        }

        return productRepository.findAll().stream()
            .map(p -> new Scored(p, scoreMatch(p, normalized, tokens)))
            .filter(s -> s.score() > 0)
            .sorted(Comparator.<Scored>comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.product().getPrice(), Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(s -> s.product().getId(), Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(effectiveLimit)
            .map(Scored::product)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Product> trending(int limit) {
        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        return productRepository.findAll().stream()
            .sorted(Comparator.<Product, BigDecimal>comparing(Product::getPrice, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Product::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(effectiveLimit)
            .toList();
    }

    @Transactional(readOnly = true)
    public List<Product> search(String query, int limit, double threshold) {
        return searchByNameAndCategory(query, limit);
    }

    private boolean equalsIgnoreCase(String left, String right) {
        if (left == null || right == null) {
            return left == null && right == null;
        }
        return left.equalsIgnoreCase(right);
    }

    private String normalizeQuery(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        return raw.trim().toLowerCase();
    }

    private List<String> tokenize(String normalizedLower) {
        if (!StringUtils.hasText(normalizedLower)) {
            return List.of();
        }
        return TOKEN_SPLIT.splitAsStream(normalizedLower)
            .map(String::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .limit(8)
            .toList();
    }

    private int scoreMatch(Product product, String normalizedLowerQuery, List<String> tokens) {
        if (product == null || !StringUtils.hasText(normalizedLowerQuery)) {
            return 0;
        }
        String name = normalizeQuery(product.getName());
        String category = normalizeQuery(product.getCategory());

        int score = 0;
        if (StringUtils.hasText(name) && name.contains(normalizedLowerQuery)) {
            score += 10;
        }
        if (StringUtils.hasText(category) && category.contains(normalizedLowerQuery)) {
            score += 6;
        }

        for (String token : tokens) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (StringUtils.hasText(name) && name.contains(token)) {
                score += 2;
            }
            if (StringUtils.hasText(category) && category.contains(token)) {
                score += 1;
            }
        }

        return score;
    }

    private void publishUpsert(Product saved) {
        if (saved == null || saved.getSku() == null) {
            return;
        }
        eventPublisher.publishEvent(new ProductUpsertIndexingEvent(
            saved.getSku(),
            saved.getName(),
            saved.getDescription(),
            saved.getCategory(),
            saved.getTags(),
            saved.getPrice(),
            saved.getCurrency(),
            saved.getInStockQty()
        ));
    }
}
