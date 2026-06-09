package com.ai.fabric.realapps.chat.catalog.model;

import java.math.BigDecimal;
import java.util.List;

public final class ProductComparisonModels {

    private ProductComparisonModels() {
    }

    public record ProductReadModel(
        Long id,
        String sku,
        String name,
        String description,
        String category,
        List<String> tags,
        String imageUrl,
        BigDecimal price,
        String currency,
        Integer inStockQty,
        double averageRating,
        long reviewCount
    ) { }

    public record SimilarProductMatch(
        ProductReadModel product,
        int similarityScore,
        List<String> matchReasons
    ) { }

    public record SimilarProductsResponse(
        ProductReadModel referenceProduct,
        List<SimilarProductMatch> items,
        int count
    ) { }

    public record ComparisonHighlights(
        BigDecimal comparisonPriceDelta,
        String cheaperSku,
        Double comparisonRatingDelta,
        String higherRatedSku,
        Integer comparisonStockDelta,
        String betterStockedSku
    ) { }

    public record ProductComparisonResponse(
        ProductReadModel referenceProduct,
        ProductReadModel comparisonProduct,
        boolean sameCategory,
        List<String> sharedTags,
        ComparisonHighlights highlights,
        List<String> keyDifferences
    ) { }
}
