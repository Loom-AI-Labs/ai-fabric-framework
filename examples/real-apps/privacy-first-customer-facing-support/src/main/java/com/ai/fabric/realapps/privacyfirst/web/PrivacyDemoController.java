package com.ai.fabric.realapps.privacyfirst.web;

import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.CreateSessionRequest;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.DemoDashboard;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.DemoSample;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.MessageResult;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.SearchResult;
import com.ai.fabric.realapps.privacyfirst.service.PrivacyDemoService.SubmitMessageRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/privacy-demo")
@RequiredArgsConstructor
public class PrivacyDemoController {

    private final PrivacyDemoService privacyDemoService;

    @PostMapping("/sessions")
    public DemoDashboard createSession(@RequestBody(required = false) CreateSessionRequest request) {
        return privacyDemoService.createSession(request != null ? request.sessionId() : null);
    }

    @GetMapping("/dashboard")
    public DemoDashboard dashboard(@RequestParam String sessionId) {
        return privacyDemoService.dashboard(sessionId);
    }

    @GetMapping("/samples")
    public List<DemoSample> samples() {
        return privacyDemoService.samples();
    }

    @PostMapping("/messages")
    public MessageResult submitMessage(@Valid @RequestBody SubmitMessageRequest request) {
        return privacyDemoService.submitMessage(request);
    }

    @GetMapping("/search")
    public SearchResult search(
        @RequestParam String sessionId,
        @RequestParam String q,
        @RequestParam(defaultValue = "6") int limit
    ) {
        return privacyDemoService.search(sessionId, q, limit);
    }
}
