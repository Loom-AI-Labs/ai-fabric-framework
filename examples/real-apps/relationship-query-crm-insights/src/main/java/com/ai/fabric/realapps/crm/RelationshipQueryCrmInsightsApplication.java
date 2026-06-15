package com.ai.fabric.realapps.crm;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class RelationshipQueryCrmInsightsApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelationshipQueryCrmInsightsApplication.class, args);
    }
}
