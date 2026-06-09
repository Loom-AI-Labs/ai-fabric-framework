package com.ai.fabric.realapps.faq.web;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.service.FaqArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final FaqArticleService faqArticleService;

    @PostMapping("/seed")
    public List<FaqArticle> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        faqArticleService.create(
            "How do I cancel my subscription?",
            "You can cancel anytime from Account → Billing → Cancel Subscription. Your plan remains active until the end of the billing period.",
            "Billing",
            List.of("cancel", "subscription", "billing")
        );
        faqArticleService.create(
            "Do you offer refunds?",
            "Refunds are available within 14 days of purchase for monthly plans. Annual plans are eligible for prorated refunds only in specific cases.",
            "Billing",
            List.of("refund", "billing", "policy")
        );
        faqArticleService.create(
            "How can I update my payment method?",
            "Go to Account → Billing → Payment Method and add a new card. You can also remove old cards from the same page.",
            "Billing",
            List.of("payment", "card", "billing")
        );
        faqArticleService.create(
            "Why am I not receiving verification emails?",
            "Check your spam folder and ensure your domain allows our emails. You can also resend verification from Account → Security.",
            "Account",
            List.of("email", "verification", "account")
        );
        faqArticleService.create(
            "How do I change my plan tier?",
            "You can upgrade or downgrade from Account → Plans. Downgrades take effect next billing cycle; upgrades apply immediately.",
            "Plans",
            List.of("upgrade", "downgrade", "plans")
        );

        return faqArticleService.list();
    }

    @PostMapping("/indexing/reindex/articles")
    public Map<String, Object> reindex(@RequestBody(required = false) Map<String, Object> ignored) {
        int count = faqArticleService.reindexAll();
        return Map.of("indexed", count);
    }
}
