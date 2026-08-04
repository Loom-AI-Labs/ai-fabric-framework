package com.ai.fabric.realapps.deploymentguard;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAIInfrastructure
@EnableScheduling
public class DeploymentKnowledgeGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DeploymentKnowledgeGuardApplication.class, args);
    }
}
