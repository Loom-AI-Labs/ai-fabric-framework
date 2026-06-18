package com.ai.fabric.realapps.migrationcatalog.service;

import com.ai.fabric.realapps.migrationcatalog.domain.Product;
import com.ai.fabric.realapps.migrationcatalog.repo.ProductRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductCatalogService {

    private final ProductRepository productRepository;
    private final AICoreService aiCoreService;

    @Transactional
    public int seedProducts(int count) {
        if (count <= 0) {
            return 0;
        }

        Random random = new Random(42);
        List<String> categories = List.of("Laptops", "Headphones", "Keyboards", "Monitors", "Storage", "Networking");
        List<String> adjectives = List.of("Portable", "Premium", "Wireless", "Compact", "Ultra", "Pro", "Essential", "Rugged");
        List<String> nouns = List.of("Laptop", "Headset", "Keyboard", "Monitor", "SSD", "Router", "Dock", "Webcam");

        int batchSize = 500;
        for (int start = 0; start < count; start += batchSize) {
            int end = Math.min(count, start + batchSize);
            List<Product> batch = java.util.stream.IntStream.range(start, end)
                .mapToObj(i -> buildProduct(i, random, categories, adjectives, nouns))
                .toList();
            productRepository.saveAll(batch);
            productRepository.flush();
            log.info("Seeded {} / {} products", end, count);
        }

        return count;
    }

    public long count() {
        return productRepository.count();
    }

    public List<Product> search(String query, int limit, double threshold) {
        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .query(query)
            .entityType("product")
            .limit(limit)
            .threshold(threshold)
            .build());

        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }

        return response.getResults().stream()
            .map(this::extractEntityId)
            .flatMap(Optional::stream)
            .map(this::findProductByEntityId)
            .flatMap(Optional::stream)
            .limit(limit)
            .toList();
    }

    private Product buildProduct(int i,
                                 Random random,
                                 List<String> categories,
                                 List<String> adjectives,
                                 List<String> nouns) {
        String category = categories.get(random.nextInt(categories.size()));
        String adjective = adjectives.get(random.nextInt(adjectives.size()));
        String noun = nouns.get(random.nextInt(nouns.size()));
        String sku = "SKU-" + String.format("%06d", i + 1);
        String name = adjective + " " + noun + " " + (100 + random.nextInt(900));
        String description = """
            %s %s designed for %s workloads.
            Highlights: fast setup, dependable performance, and simple troubleshooting.
            Common questions: refunds, warranty, shipping, and cancellation policy for accessories.
            """.formatted(adjective, noun, category.toLowerCase());

        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setDescription(description);
        product.setCategory(category);
        product.setPrice(BigDecimal.valueOf(49 + random.nextInt(950) + random.nextDouble()).setScale(2, RoundingMode.HALF_UP));
        product.setCreatedAt(LocalDateTime.now().minusDays(random.nextInt(180)));
        return product;
    }

    private Optional<String> extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        return firstPresent(row.get("entityId"), row.get("id"))
            .map(Objects::toString)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> firstPresent(Object first, Object second) {
        return Optional.ofNullable(first != null ? first : second);
    }

    private Optional<Product> findProductByEntityId(String id) {
        try {
            return productRepository.findById(Long.parseLong(id));
        } catch (NumberFormatException ex) {
            log.debug("Unable to parse product entityId '{}' as Long", id);
            return Optional.empty();
        }
    }
}
