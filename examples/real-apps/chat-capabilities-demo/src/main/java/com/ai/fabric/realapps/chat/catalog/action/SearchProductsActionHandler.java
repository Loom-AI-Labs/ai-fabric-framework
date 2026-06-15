package com.ai.fabric.realapps.chat.catalog.action;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "search_products",
    description = "Search products by keyword (matches product title + category). Falls back to trending products.",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class SearchProductsActionHandler {

    private final ProductService productService;

    @ActionExecute
    public ActionResult execute(
        @Param(value = "query", description = "Search query", required = true) String query,
        ActionContext context
    ) {
        try {
            List<Product> results = productService.searchByNameAndCategory(query, 10);
            boolean matched = !results.isEmpty();
            if (!matched) {
                results = productService.trending(10);
            }

            List<Map<String, Object>> payload = results.stream()
                .map(SearchProductsActionHandler::toPayload)
                .toList();

            return ActionResult.builder()
                .success(true)
                .message(payload.isEmpty()
                    ? "No products found"
                    : (matched ? "Matching products" : "Trending products"))
                .data(ActionResultContracts.list(payload))
                .build();
        } catch (Exception e) {
            String userId = context != null ? context.userId() : null;
            log.error("Search products failed for user {}", userId, e);
            String details = e != null && e.getMessage() != null ? e.getMessage() : (e != null ? e.getClass().getSimpleName() : "unknown");
            return ActionResult.builder()
                .success(false)
                .message("Failed to search products: " + details)
                .errorCode("SEARCH_PRODUCTS_FAILED")
                .build();
        }
    }

    private static Map<String, Object> toPayload(Product product) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", product != null ? product.getId() : null);
        out.put("sku", product != null ? product.getSku() : null);
        out.put("name", product != null ? product.getName() : null);
        out.put("description", product != null ? product.getDescription() : null);
        out.put("category", product != null ? product.getCategory() : null);
        out.put("tags", product != null ? product.getTags() : null);
        out.put("imageUrl", product != null ? product.getImageUrl() : null);
        out.put("price", product != null ? product.getPrice() : null);
        out.put("currency", product != null ? product.getCurrency() : null);
        out.put("inStockQty", product != null ? product.getInStockQty() : null);
        out.put("createdAt", product != null ? product.getCreatedAt() : null);
        out.put("updatedAt", product != null ? product.getUpdatedAt() : null);
        return Collections.unmodifiableMap(out);
    }
}
