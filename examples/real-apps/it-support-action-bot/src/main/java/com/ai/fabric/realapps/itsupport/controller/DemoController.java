package com.ai.fabric.realapps.itsupport.controller;

import com.ai.fabric.realapps.itsupport.config.DataSeeder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final DataSeeder dataSeeder;

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Object>> seed() {
        dataSeeder.seedIfEmpty();
        return ResponseEntity.ok(Map.of("seeded", true));
    }
}
