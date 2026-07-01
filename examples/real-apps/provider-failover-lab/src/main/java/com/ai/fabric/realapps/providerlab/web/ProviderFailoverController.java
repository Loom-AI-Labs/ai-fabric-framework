package com.ai.fabric.realapps.providerlab.web;

import com.ai.fabric.realapps.providerlab.service.ProviderFailoverService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/provider-lab")
@RequiredArgsConstructor
public class ProviderFailoverController {

    private final ProviderFailoverService service;

    @PostMapping("/probe")
    public ProviderFailoverService.ProviderProbeResult probe(
        @RequestBody ProviderFailoverService.ProviderProbeRequest request
    ) {
        return service.runProbe(request);
    }
}
