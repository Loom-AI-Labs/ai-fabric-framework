package com.ai.fabric.realapps.chat.demo.service;

import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.repository.IndexingQueueRepository;
import com.ai.fabric.realapps.chat.catalog.repo.ProductRepository;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import com.ai.fabric.realapps.chat.promotions.repo.CouponRepository;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import com.ai.fabric.realapps.chat.support.repo.SupportTicketRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemoReadinessService {

    private static final List<String> VECTOR_SPACES = List.of("product", "review", "policy");

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PolicyRepository policyRepository;
    private final CouponRepository couponRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final ObjectProvider<VectorDatabaseService> vectorDatabaseServiceProvider;
    private final ObjectProvider<IndexingQueueRepository> indexingQueueRepositoryProvider;

    public ReadinessReport readiness() {
        long products = productRepository.count();
        long reviews = reviewRepository.count();
        long policies = policyRepository.count();
        long coupons = couponRepository.count();
        long tickets = supportTicketRepository.count();

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("products", products);
        counts.put("reviews", reviews);
        counts.put("policies", policies);
        counts.put("coupons", coupons);
        counts.put("tickets", tickets);

        Map<String, VectorSpaceStatus> vectorSpaces = vectorSpaceStatus();
        long indexingQueueSize = indexingQueueSize();
        String stage = resolveStage(products, reviews, policies, coupons, tickets);

        return ReadinessReport.builder()
            .ready("full".equals(stage))
            .stage(stage)
            .stageNumber(stageNumber(stage))
            .counts(counts)
            .vectorSpaces(vectorSpaces)
            .indexingQueueSize(indexingQueueSize)
            .nextRecommendedStep(nextRecommendedStep(stage))
            .warnings(warnings(counts, vectorSpaces, indexingQueueSize))
            .checkedAt(Instant.now().toString())
            .build();
    }

    private Map<String, VectorSpaceStatus> vectorSpaceStatus() {
        VectorDatabaseService vectorDatabaseService = vectorDatabaseServiceProvider.getIfAvailable();
        Map<String, VectorSpaceStatus> out = new LinkedHashMap<>();
        if (vectorDatabaseService == null) {
            for (String space : VECTOR_SPACES) {
                out.put(space, VectorSpaceStatus.builder()
                    .name(space)
                    .present(false)
                    .vectorCount(0L)
                    .warning("VectorDatabaseService is not available")
                    .build());
            }
            return out;
        }

        for (String space : VECTOR_SPACES) {
            try {
                long count = vectorDatabaseService.getVectorCountByEntityType(space);
                out.put(space, VectorSpaceStatus.builder()
                    .name(space)
                    .present(count > 0)
                    .vectorCount(count)
                    .provider(vectorDatabaseService.vectorProviderName())
                    .searchFilterMode(vectorDatabaseService.vectorSearchFilterMode())
                    .scanFilterMode(vectorDatabaseService.vectorScanFilterMode())
                    .build());
            } catch (Exception ex) {
                out.put(space, VectorSpaceStatus.builder()
                    .name(space)
                    .present(false)
                    .vectorCount(0L)
                    .provider(vectorDatabaseService.vectorProviderName())
                    .warning(ex.getMessage())
                    .build());
            }
        }
        return out;
    }

    private long indexingQueueSize() {
        IndexingQueueRepository repository = indexingQueueRepositoryProvider.getIfAvailable();
        if (repository == null) {
            return 0L;
        }
        try {
            return repository.count();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String resolveStage(long products, long reviews, long policies, long coupons, long tickets) {
        if (products <= 0) {
            return "empty";
        }
        if (reviews <= 0) {
            return "products";
        }
        if (policies <= 0) {
            return "reviews";
        }
        if (coupons <= 0) {
            return "policies";
        }
        if (tickets <= 0) {
            return "coupons";
        }
        return "full";
    }

    private int stageNumber(String stage) {
        return switch (stage) {
            case "empty" -> 0;
            case "products" -> 1;
            case "reviews" -> 2;
            case "policies" -> 3;
            case "coupons" -> 4;
            default -> 5;
        };
    }

    private String nextRecommendedStep(String stage) {
        return switch (stage) {
            case "empty" -> "Seed product catalog evidence";
            case "products" -> "Seed product reviews for comparison evidence";
            case "reviews" -> "Seed shopping policies for governed answers";
            case "policies" -> "Seed coupons for discount scenarios";
            case "coupons" -> "Seed support tickets for full commerce scenarios";
            default -> "Run RAG, cart, order, and support smoke scenarios";
        };
    }

    private List<String> warnings(Map<String, Long> counts,
                                  Map<String, VectorSpaceStatus> vectorSpaces,
                                  long indexingQueueSize) {
        java.util.ArrayList<String> warnings = new java.util.ArrayList<>();
        if (counts.getOrDefault("products", 0L) > 0 && vectorSpaces.get("product").getVectorCount() == 0) {
            warnings.add("Products exist in the database but product vectors are not visible yet.");
        }
        if (counts.getOrDefault("reviews", 0L) > 0 && vectorSpaces.get("review").getVectorCount() == 0) {
            warnings.add("Reviews exist in the database but review vectors are not visible yet.");
        }
        if (counts.getOrDefault("policies", 0L) > 0 && vectorSpaces.get("policy").getVectorCount() == 0) {
            warnings.add("Policies exist in the database but policy vectors are not visible yet.");
        }
        if (indexingQueueSize > 0) {
            warnings.add("Indexing queue still has " + indexingQueueSize + " entries; retrieval may lag briefly.");
        }
        return warnings;
    }

    @Data
    @Builder
    public static class ReadinessReport {
        private boolean ready;
        private String stage;
        private int stageNumber;
        private Map<String, Long> counts;
        private Map<String, VectorSpaceStatus> vectorSpaces;
        private long indexingQueueSize;
        private String nextRecommendedStep;
        private List<String> warnings;
        private String checkedAt;
    }

    @Data
    @Builder
    public static class VectorSpaceStatus {
        private String name;
        private boolean present;
        private long vectorCount;
        private String provider;
        private String searchFilterMode;
        private String scanFilterMode;
        private String warning;
    }
}
