package com.ai.fabric.realapps.chat.catalog.service;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ComparisonHighlights;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ProductComparisonResponse;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.ProductReadModel;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.SimilarProductMatch;
import com.ai.fabric.realapps.chat.catalog.model.ProductComparisonModels.SimilarProductsResponse;
import com.ai.fabric.realapps.chat.reviews.domain.Review;
import com.ai.fabric.realapps.chat.reviews.repo.ReviewRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ProductComparisonService {

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    private final ProductService productService;
    private final ReviewRepository reviewRepository;

    @Transactional(readOnly = true)
    public SimilarProductsResponse findSimilarProducts(String sku, int limit) {
        Product reference = requireProduct(sku);
        int effectiveLimit = limit <= 0 ? 5 : Math.min(limit, 10);
        Map<String, ReviewStats> statsBySku = reviewStatsBySku();
        ProductReadModel referenceView = toReadModel(reference, statsBySku);

        List<ScoredProduct> scoredCandidates = productService.list(500).stream()
            .filter(candidate -> !sameSku(reference, candidate))
            .map(candidate -> scoreCandidate(reference, candidate))
            .sorted(Comparator.comparingInt(ScoredProduct::score).reversed()
                .thenComparing(item -> item.product().getPrice(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.product().getId(), Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        List<ScoredProduct> selected = scoredCandidates.stream()
            .filter(item -> item.score() > 0)
            .limit(effectiveLimit)
            .toList();
        if (selected.isEmpty()) {
            selected = scoredCandidates.stream().limit(effectiveLimit).toList();
        }

        List<SimilarProductMatch> items = selected.stream()
            .map(item -> new SimilarProductMatch(
                toReadModel(item.product(), statsBySku),
                item.score(),
                item.reasons()
            ))
            .toList();

        return new SimilarProductsResponse(referenceView, items, items.size());
    }

    @Transactional(readOnly = true)
    public ProductComparisonResponse compareProducts(String referenceSku, String comparisonSku) {
        Product reference = requireProduct(referenceSku);
        Product comparison = requireProduct(comparisonSku);
        Map<String, ReviewStats> statsBySku = reviewStatsBySku();

        ProductReadModel referenceView = toReadModel(reference, statsBySku);
        ProductReadModel comparisonView = toReadModel(comparison, statsBySku);
        List<String> sharedTags = sharedTags(reference, comparison);
        boolean sameCategory = equalNormalized(reference.getCategory(), comparison.getCategory());

        BigDecimal comparisonPriceDelta = subtractNullable(comparison.getPrice(), reference.getPrice());
        String cheaperSku = cheaperSku(referenceView, comparisonView);
        Double comparisonRatingDelta = roundedDelta(comparisonView.averageRating(), referenceView.averageRating());
        String higherRatedSku = higherRatedSku(referenceView, comparisonView);
        Integer comparisonStockDelta = stockDelta(comparisonView.inStockQty(), referenceView.inStockQty());
        String betterStockedSku = betterStockedSku(referenceView, comparisonView);

        ComparisonHighlights highlights = new ComparisonHighlights(
            comparisonPriceDelta,
            cheaperSku,
            comparisonRatingDelta,
            higherRatedSku,
            comparisonStockDelta,
            betterStockedSku
        );

        return new ProductComparisonResponse(
            referenceView,
            comparisonView,
            sameCategory,
            sharedTags,
            highlights,
            comparisonNarrative(referenceView, comparisonView, sameCategory, sharedTags, highlights)
        );
    }

    private Product requireProduct(String sku) {
        String normalizedSku = SkuNormalizer.normalizeForLookup(sku);
        if (!StringUtils.hasText(normalizedSku)) {
            throw new IllegalArgumentException("sku is required");
        }
        return productService.findBySku(normalizedSku)
            .orElseThrow(() -> new EntityNotFoundException("Product not found for sku: " + normalizedSku));
    }

    private Map<String, ReviewStats> reviewStatsBySku() {
        return reviewRepository.findAll().stream()
            .filter(review -> StringUtils.hasText(review.getSku()))
            .collect(Collectors.groupingBy(
                review -> normalize(review.getSku()),
                Collectors.collectingAndThen(Collectors.toList(), this::reviewStats)
            ));
    }

    private ReviewStats reviewStats(List<Review> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return ReviewStats.EMPTY;
        }
        double average = reviews.stream()
            .map(Review::getRating)
            .filter(Objects::nonNull)
            .mapToInt(Integer::intValue)
            .average()
            .orElse(0.0d);
        return new ReviewStats(round(average), reviews.size());
    }

    private ProductReadModel toReadModel(Product product, Map<String, ReviewStats> statsBySku) {
        ReviewStats stats = statsBySku.getOrDefault(normalize(product.getSku()), ReviewStats.EMPTY);
        return new ProductReadModel(
            product.getId(),
            product.getSku(),
            product.getName(),
            product.getDescription(),
            product.getCategory(),
            List.copyOf(parseTags(product.getTags())),
            product.getImageUrl(),
            product.getPrice(),
            product.getCurrency(),
            product.getInStockQty(),
            stats.averageRating(),
            stats.reviewCount()
        );
    }

    private ScoredProduct scoreCandidate(Product reference, Product candidate) {
        List<String> reasons = new ArrayList<>();
        int score = 0;

        if (equalNormalized(reference.getCategory(), candidate.getCategory())) {
            score += 40;
            reasons.add("same-category");
        }

        Set<String> sharedTags = intersect(parseTags(reference.getTags()), parseTags(candidate.getTags()));
        if (!sharedTags.isEmpty()) {
            score += Math.min(20, sharedTags.size() * 5);
            reasons.add("shared-tags:" + String.join("|", sharedTags));
        }

        if (closePrice(reference.getPrice(), candidate.getPrice(), new BigDecimal("0.10"))) {
            score += 12;
            reasons.add("similar-price");
        } else if (closePrice(reference.getPrice(), candidate.getPrice(), new BigDecimal("0.25"))) {
            score += 6;
            reasons.add("comparable-price");
        }

        if (bothInStock(reference, candidate)) {
            score += 5;
            reasons.add("both-in-stock");
        }

        Set<String> sharedNameTokens = intersect(nameTokens(reference.getName()), nameTokens(candidate.getName()));
        if (!sharedNameTokens.isEmpty()) {
            score += Math.min(12, sharedNameTokens.size() * 3);
            reasons.add("shared-keywords:" + String.join("|", sharedNameTokens));
        }

        return new ScoredProduct(candidate, score, List.copyOf(reasons));
    }

    private List<String> comparisonNarrative(ProductReadModel reference,
                                             ProductReadModel comparison,
                                             boolean sameCategory,
                                             List<String> sharedTags,
                                             ComparisonHighlights highlights) {
        List<String> notes = new ArrayList<>();
        if (sameCategory && StringUtils.hasText(reference.category())) {
            notes.add("Both products are in the " + reference.category() + " category.");
        }
        if (!sharedTags.isEmpty()) {
            notes.add("Shared tags: " + String.join(", ", sharedTags) + ".");
        }
        if (highlights.cheaperSku() != null && highlights.comparisonPriceDelta() != null) {
            notes.add(highlights.cheaperSku() + " is cheaper by " + absolute(highlights.comparisonPriceDelta()) + ".");
        }
        if (highlights.higherRatedSku() != null && highlights.comparisonRatingDelta() != null) {
            notes.add(highlights.higherRatedSku() + " has the stronger review rating signal.");
        }
        if (highlights.betterStockedSku() != null && highlights.comparisonStockDelta() != null) {
            notes.add(highlights.betterStockedSku() + " has better live stock availability.");
        }
        if (notes.isEmpty()) {
            notes.add("These products can be compared on price, inventory, category, and review signal.");
        }
        return List.copyOf(notes);
    }

    private List<String> sharedTags(Product left, Product right) {
        return List.copyOf(intersect(parseTags(left.getTags()), parseTags(right.getTags())));
    }

    private Set<String> parseTags(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        return TOKEN_SPLIT.splitAsStream(raw.toLowerCase(Locale.ROOT))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .limit(12)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> nameTokens(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        return TOKEN_SPLIT.splitAsStream(raw.toLowerCase(Locale.ROOT))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .limit(8)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> intersect(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>(left);
        result.retainAll(right);
        return result;
    }

    private boolean sameSku(Product left, Product right) {
        return equalNormalized(left.getSku(), right.getSku());
    }

    private boolean equalNormalized(String left, String right) {
        return Objects.equals(normalize(left), normalize(right));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private boolean bothInStock(Product left, Product right) {
        return safeStock(left.getInStockQty()) > 0 && safeStock(right.getInStockQty()) > 0;
    }

    private boolean closePrice(BigDecimal left, BigDecimal right, BigDecimal toleranceRatio) {
        if (left == null || right == null || toleranceRatio == null) {
            return false;
        }
        BigDecimal baseline = left.max(right);
        if (baseline.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        BigDecimal delta = left.subtract(right).abs();
        return delta.compareTo(baseline.multiply(toleranceRatio)) <= 0;
    }

    private BigDecimal subtractNullable(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.subtract(right).setScale(2, RoundingMode.HALF_UP);
    }

    private String cheaperSku(ProductReadModel reference, ProductReadModel comparison) {
        if (reference.price() == null || comparison.price() == null) {
            return null;
        }
        int comparisonResult = reference.price().compareTo(comparison.price());
        if (comparisonResult == 0) {
            return null;
        }
        return comparisonResult < 0 ? reference.sku() : comparison.sku();
    }

    private Double roundedDelta(double left, double right) {
        return round(left - right);
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private String higherRatedSku(ProductReadModel reference, ProductReadModel comparison) {
        if (reference.reviewCount() <= 0 && comparison.reviewCount() <= 0) {
            return null;
        }
        if (Double.compare(reference.averageRating(), comparison.averageRating()) == 0) {
            return null;
        }
        return reference.averageRating() > comparison.averageRating() ? reference.sku() : comparison.sku();
    }

    private Integer stockDelta(Integer left, Integer right) {
        if (left == null || right == null) {
            return null;
        }
        return left - right;
    }

    private String betterStockedSku(ProductReadModel reference, ProductReadModel comparison) {
        Integer referenceStock = reference.inStockQty();
        Integer comparisonStock = comparison.inStockQty();
        if (referenceStock == null || comparisonStock == null || Objects.equals(referenceStock, comparisonStock)) {
            return null;
        }
        return referenceStock > comparisonStock ? reference.sku() : comparison.sku();
    }

    private int safeStock(Integer value) {
        return value == null ? 0 : value;
    }

    private String absolute(BigDecimal value) {
        return value == null ? "" : value.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private record ReviewStats(double averageRating, long reviewCount) {
        private static final ReviewStats EMPTY = new ReviewStats(0.0d, 0L);
    }

    private record ScoredProduct(Product product, int score, List<String> reasons) { }
}
