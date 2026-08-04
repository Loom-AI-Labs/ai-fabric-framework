package com.ai.fabric.realapps.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class McpOperationsReferenceServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(
            McpOperationsReferenceServerApplication.class,
            args
        );
    }
}
