package com.ai.fabric.realapps.cloudvector.web;

import com.ai.fabric.realapps.cloudvector.service.SampleDataSeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final SampleDataSeeder seeder;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        int seeded = seeder.seed();
        return Map.of("seededArticles", seeded);
    }
}
