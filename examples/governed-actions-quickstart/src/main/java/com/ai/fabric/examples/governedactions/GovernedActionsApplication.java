package com.ai.fabric.examples.governedactions;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class GovernedActionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(GovernedActionsApplication.class, args);
    }
}