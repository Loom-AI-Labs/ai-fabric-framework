package com.ai.fabric.realapps.cloudvector.service;

import com.ai.fabric.realapps.cloudvector.repo.KnowledgeBaseArticleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SampleDataSeeder {

    private final KnowledgeBaseArticleRepository repository;
    private final KnowledgeBaseArticleService articleService;

    @Transactional
    public int seed() {
        repository.deleteAll();

        // Keep the seed scenario deterministic and minimal.
        articleService.create(
            "Resetting your password",
            "If you forgot your password, click 'Forgot Password' on the login screen. You will receive a reset link by email.",
            "auth",
            List.of("login", "password", "account")
        );

        articleService.create(
            "Troubleshooting MFA issues",
            "If you cannot access your MFA device, use backup codes or contact support to reset MFA after verification.",
            "security",
            List.of("mfa", "2fa", "security")
        );

        articleService.create(
            "Billing: resolve a failed payment",
            "Update your card details in Billing Settings. If the issue persists, contact your bank or retry in 24 hours.",
            "billing",
            List.of("billing", "payment", "invoice")
        );

        articleService.create(
            "Cancel your subscription",
            "Go to Subscription Settings and select 'Cancel'. Your plan remains active until the end of the billing cycle.",
            "subscription",
            List.of("cancel", "subscription")
        );

        articleService.create(
            "Exporting data for compliance",
            "Admins can export account data from the Admin Console. Exports are available for 7 days and include audit logs.",
            "compliance",
            List.of("export", "gdpr", "audit")
        );

        return (int) repository.count();
    }
}
