package com.ai.fabric.realapps.chat.catalog.action;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.annotation.AIAction;
import com.ai.infrastructure.intent.action.annotation.ActionExecute;
import com.ai.infrastructure.intent.action.annotation.Param;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@AIAction(
    name = "list_products",
    description = "List products by query (matches product title + category). Falls back to trending products.",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class ListProductsActionHandler {

    private final ProductService productService;

    @ActionExecute
    public ActionResult execute(
        @Param(value = "query", description = "What products are you looking for?", required = true) String query,
        ActionContext context
    ) {
        try {
            List<Product> products = productService.searchByNameAndCategory(query, 10);
            boolean matched = !products.isEmpty();
            if (!matched) {
                products = productService.trending(10);
            }

            List<Map<String, Object>> payload = products.stream()
                .map(ListProductsActionHandler::toPayload)
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
            log.error("List products failed for user {}", userId, e);
            String details = e != null && e.getMessage() != null ? e.getMessage() : (e != null ? e.getClass().getSimpleName() : "unknown");
            return ActionResult.builder()
                .success(false)
                .message("Failed to list products: " + details)
                .errorCode("LIST_PRODUCTS_FAILED")
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
