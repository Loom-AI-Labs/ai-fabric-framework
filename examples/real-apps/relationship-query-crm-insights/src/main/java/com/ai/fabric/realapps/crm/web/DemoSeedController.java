package com.ai.fabric.realapps.crm.web;

import com.ai.fabric.realapps.crm.service.CrmSeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoSeedController {

    private final CrmSeedService seedService;

    @PostMapping("/seed")
    public Map<String, Object> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        return seedService.seed();
    }
}
