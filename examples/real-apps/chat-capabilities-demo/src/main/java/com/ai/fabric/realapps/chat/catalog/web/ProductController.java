package com.ai.fabric.realapps.chat.catalog.web;

import com.ai.fabric.realapps.chat.catalog.domain.Product;
import com.ai.fabric.realapps.chat.catalog.service.ProductService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("totalProducts", productService.count());
    }

    @GetMapping
    public List<Product> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return productService.list(limit);
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable long id) {
        return productService.get(id);
    }

    @GetMapping("/search")
    public List<Product> search(@RequestParam("q") String query,
                                @RequestParam(value = "limit", defaultValue = "10") int limit,
                                @RequestParam(value = "threshold", defaultValue = "0.3") double threshold) {
        return productService.search(query, limit, threshold);
    }

    @GetMapping("/trending")
    public List<Product> trending(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return productService.trending(limit);
    }

    @PostMapping
    public ResponseEntity<Product> create(@Valid @RequestBody CreateProductRequest request) {
        Product created = productService.createProduct(
            request.getSku(),
            request.getName(),
            request.getDescription(),
            request.getCategory(),
            request.getTags(),
            request.getImageUrl(),
            request.getPrice(),
            request.getCurrency(),
            request.getInStockQty()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Product update(@PathVariable long id, @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateProduct(
            id,
            request.getSku(),
            request.getName(),
            request.getDescription(),
            request.getCategory(),
            request.getTags(),
            request.getImageUrl(),
            request.getPrice(),
            request.getCurrency(),
            request.getInStockQty()
        );
    }

    @DeleteMapping("/{id}")
    public Product delete(@PathVariable long id) {
        return productService.deleteProduct(id);
    }

    @Data
    public static class CreateProductRequest {
        @NotBlank
        private String sku;
        @NotBlank
        private String name;
        @NotBlank
        private String description;
        private String category;
        private String tags;
        private String imageUrl;
        @NotNull
        private BigDecimal price;
        private String currency = "USD";
        private Integer inStockQty = 0;
    }

    @Data
    public static class UpdateProductRequest {
        private String sku;
        private String name;
        private String description;
        private String category;
        private String tags;
        private String imageUrl;
        private BigDecimal price;
        private String currency;
        private Integer inStockQty;
    }
}
