package com.ai.fabric.realapps.migrationcatalog.web;

import com.ai.fabric.realapps.migrationcatalog.domain.Product;
import com.ai.fabric.realapps.migrationcatalog.repo.ProductRepository;
import com.ai.fabric.realapps.migrationcatalog.service.ProductCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductSearchController {

    private final ProductCatalogService productCatalogService;
    private final ProductRepository productRepository;

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("totalProducts", productCatalogService.count());
    }

    @GetMapping
    public List<Product> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return productRepository.findAll().stream().limit(limit).toList();
    }

    @GetMapping("/{id}")
    public Product get(@PathVariable long id) {
        return productRepository.findById(id).orElseThrow();
    }

    @GetMapping("/search")
    public List<Product> search(@RequestParam("q") String query,
                                @RequestParam(value = "limit", defaultValue = "10") int limit,
                                @RequestParam(value = "threshold", defaultValue = "0.3") double threshold) {
        return productCatalogService.search(query, limit, threshold);
    }
}
