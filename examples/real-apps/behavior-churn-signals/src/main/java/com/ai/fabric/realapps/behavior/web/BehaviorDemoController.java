package com.ai.fabric.realapps.behavior.web;

import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/behavior-demo")
@RequiredArgsConstructor
public class BehaviorDemoController {

    private final BehaviorDemoScenarioService service;

    @GetMapping("/dashboard")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard dashboard() {
        return service.dashboard();
    }

    @GetMapping("/scenarios")
    public java.util.List<BehaviorDemoScenarioService.DemoScenarioSummary> scenarios() {
        return service.dashboard().scenarios();
    }

    @PostMapping("/seed")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard seed() {
        return service.seed();
    }

    @PostMapping("/seed-and-analyze")
    public BehaviorDemoScenarioService.BehaviorDemoDashboard seedAndAnalyze() {
        return service.seedAndAnalyze();
    }

    @PostMapping("/scenarios/{userId}/analyze")
    public BehaviorDemoScenarioService.BehaviorScenarioResult analyze(@PathVariable String userId) {
        return service.analyze(userId);
    }

    @PostMapping("/scenarios/{userId}/signals")
    public BehaviorDemoScenarioService.BehaviorScenarioResult recordSignal(
        @PathVariable String userId,
        @RequestBody(required = false) BehaviorDemoScenarioService.RecordBehaviorSignalRequest request
    ) {
        return service.recordSignal(userId, request);
    }

    @PostMapping("/scenarios/{userId}/retention-offer")
    public BehaviorDemoScenarioService.RetentionOfferDemoResult retentionOffer(
        @PathVariable String userId,
        @RequestBody(required = false) BehaviorDemoScenarioService.RetentionOfferDemoRequest request
    ) {
        return service.retentionOffer(userId, request);
    }
}
