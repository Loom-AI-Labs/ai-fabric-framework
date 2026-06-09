package com.ai.fabric.realapps.privacyfirst.web;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.service.SupportMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoSamplesController {

    private final SupportMessageService supportMessageService;

    @GetMapping("/samples")
    public List<Map<String, String>> samples() {
        return List.of(
            Map.of(
                "customerId", "cust-1001",
                "channel", "webchat",
                "subject", "Billing update request",
                "message", "Hi, my email is sara.ahmed@example.com and my phone is +1 (555) 123-4567. Please update my subscription."
            ),
            Map.of(
                "customerId", "cust-1003",
                "channel", "email",
                "subject", "Refund request",
                "message", "Order 81273 - my SSN is 123-45-6789 (for verification) and my phone is 555-222-1111."
            ),
            Map.of(
                "customerId", "cust-1004",
                "channel", "mobile",
                "subject", "Login issue",
                "message", "I cannot login after password reset. No PII here, just help me regain access."
            )
        );
    }

    @PostMapping("/seed")
    public List<SupportMessage> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        supportMessageService.create(
            "cust-1001",
            "webchat",
            "Billing update request",
            "Hi, my email is sara.ahmed@example.com and my phone is +1 (555) 123-4567. Please update my subscription."
        );
        supportMessageService.create(
            "cust-1003",
            "email",
            "Refund request",
            "Order 81273 - my SSN is 123-45-6789 (for verification) and my phone is 555-222-1111."
        );
        supportMessageService.create(
            "cust-1004",
            "mobile",
            "Login issue",
            "I cannot login after password reset. No PII here, just help me regain access."
        );
        return supportMessageService.list();
    }
}
