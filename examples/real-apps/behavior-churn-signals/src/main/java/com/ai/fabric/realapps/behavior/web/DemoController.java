package com.ai.fabric.realapps.behavior.web;

import com.ai.fabric.realapps.behavior.service.DemoEventSeeder;
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

    private final DemoEventSeeder seeder;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        long count = seeder.seed();
        return Map.of("seededEvents", count);
    }
}
