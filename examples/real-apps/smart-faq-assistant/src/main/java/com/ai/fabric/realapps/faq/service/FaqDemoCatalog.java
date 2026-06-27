package com.ai.fabric.realapps.faq.service;

import java.util.List;

public final class FaqDemoCatalog {

    private FaqDemoCatalog() {
    }

    public static List<SeedArticle> baselineArticles() {
        return List.of(
            new SeedArticle(
                "How do I cancel my subscription?",
                "You can cancel anytime from Account > Billing > Cancel Subscription. Your plan remains active until the end of the billing period.",
                "Billing",
                List.of("cancel", "subscription", "billing")
            ),
            new SeedArticle(
                "Do you offer refunds?",
                "Refunds are available within 14 days of purchase for monthly plans. Annual plans are eligible for prorated refunds only in specific cases.",
                "Billing",
                List.of("refund", "billing", "policy")
            ),
            new SeedArticle(
                "How can I update my payment method?",
                "Go to Account > Billing > Payment Method and add a new card. You can also remove old cards from the same page.",
                "Billing",
                List.of("payment", "card", "billing")
            ),
            new SeedArticle(
                "Why am I not receiving verification emails?",
                "Check your spam folder and ensure your domain allows our emails. You can also resend verification from Account > Security.",
                "Account",
                List.of("email", "verification", "account")
            ),
            new SeedArticle(
                "How do I change my plan tier?",
                "You can upgrade or downgrade from Account > Plans. Downgrades take effect next billing cycle; upgrades apply immediately.",
                "Plans",
                List.of("upgrade", "downgrade", "plans")
            )
        );
    }

    public static List<GoldenQuestion> goldenQuestions() {
        return List.of(
            new GoldenQuestion(
                "cancel-subscription",
                "How can I cancel my subscription?",
                "How do I cancel my subscription?"
            ),
            new GoldenQuestion(
                "refund-policy",
                "Can I get a refund after buying a monthly plan?",
                "Do you offer refunds?"
            ),
            new GoldenQuestion(
                "payment-method",
                "Where do I update my credit card?",
                "How can I update my payment method?"
            ),
            new GoldenQuestion(
                "verification-email",
                "I am not getting verification emails. What should I check?",
                "Why am I not receiving verification emails?"
            ),
            new GoldenQuestion(
                "change-plan",
                "How do upgrades and downgrades work?",
                "How do I change my plan tier?"
            )
        );
    }

    public record SeedArticle(
        String title,
        String content,
        String category,
        List<String> tags
    ) {}

    public record GoldenQuestion(
        String id,
        String question,
        String expectedTitle
    ) {}
}
