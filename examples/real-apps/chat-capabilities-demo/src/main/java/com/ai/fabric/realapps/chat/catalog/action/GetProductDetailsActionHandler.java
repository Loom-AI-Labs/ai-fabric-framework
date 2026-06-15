package com.ai.fabric.realapps.chat.catalog.action;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.ActionResultContracts;
import ai.fabric.intent.action.annotation.AIAction;
import ai.fabric.intent.action.annotation.ActionExecute;
import ai.fabric.intent.action.annotation.Param;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@AIAction(
    name = "get_product_details",
    description = "Get full product details by SKU",
    category = "commerce",
    accessMode = ActionAccessMode.READ,
    requiresConfirmation = false
)
@RequiredArgsConstructor
@Slf4j
public class GetProductDetailsActionHandler {

    private final ProductService productService;

    @ActionExecute
    public ActionResult execute(@Param(value = "sku", description = "Product SKU", required = true) String sku,
                                ActionContext context) {
        try {
            if (!StringUtils.hasText(sku)) {
                throw new IllegalArgumentException("Missing required parameter: sku");
            }

            Product product = productService.findBySku(sku)
                .orElseThrow(() -> new IllegalArgumentException("Product not found for sku: " + sku));

            return ActionResult.builder()
                .success(true)
                .message("Product details")
                .data(ActionResultContracts.object(Map.of(
                    "id", product.getId(),
                    "sku", product.getSku(),
                    "name", product.getName(),
                    "description", product.getDescription(),
                    "category", product.getCategory(),
                    "tags", product.getTags(),
                    "price", product.getPrice(),
                    "currency", product.getCurrency(),
                    "inStockQty", product.getInStockQty()
                )))
                .build();
        } catch (Exception e) {
            String userId = context != null ? context.userId() : null;
            log.error("Get product details failed for user {}", userId, e);
            String errorMessage = e != null && StringUtils.hasText(e.getMessage())
                ? e.getMessage()
                : e != null ? e.getClass().getSimpleName() : "unknown";
            return ActionResult.builder()
                .success(false)
                .message("Failed to fetch product details: " + errorMessage)
                .errorCode("GET_PRODUCT_DETAILS_FAILED")
                .build();
        }
    }
}
