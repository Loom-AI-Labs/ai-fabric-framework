package com.ai.fabric.realapps.mcpops;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAIInfrastructure
@EnableScheduling
public class McpOperationsAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpOperationsAssistantApplication.class, args);
    }
}
