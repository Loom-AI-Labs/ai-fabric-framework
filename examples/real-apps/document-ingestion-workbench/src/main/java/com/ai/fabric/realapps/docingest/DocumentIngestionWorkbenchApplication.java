package com.ai.fabric.realapps.docingest;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class DocumentIngestionWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentIngestionWorkbenchApplication.class, args);
    }
}
