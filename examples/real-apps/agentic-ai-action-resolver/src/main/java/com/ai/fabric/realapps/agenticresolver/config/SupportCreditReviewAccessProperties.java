package com.ai.fabric.realapps.agenticresolver.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.agentic-resolver.reviews")
public record SupportCreditReviewAccessProperties(
    String reviewerApiKey,
    String seniorReviewerApiKey
) {}
