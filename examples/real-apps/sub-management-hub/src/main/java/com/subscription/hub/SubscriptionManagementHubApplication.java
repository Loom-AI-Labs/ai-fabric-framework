package com.subscription.hub;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class SubscriptionManagementHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(SubscriptionManagementHubApplication.class, args);
    }
}
