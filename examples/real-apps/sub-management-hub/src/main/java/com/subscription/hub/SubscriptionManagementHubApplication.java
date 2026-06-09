package com.subscription.hub;

import com.ai.infrastructure.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class SubscriptionManagementHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionManagementHubApplication.class, args);
    }
}
