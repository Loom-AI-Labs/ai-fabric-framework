package com.ai.fabric.realapps.migrationcatalog.web;

import com.ai.fabric.realapps.migrationcatalog.service.ProductCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoSeedController {

    private final ProductCatalogService productCatalogService;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestParam(value = "count", defaultValue = "5000") int count) {
        int seeded = productCatalogService.seedProducts(count);
        return Map.of(
            "seeded", seeded,
            "totalProducts", productCatalogService.count()
        );
    }
}
