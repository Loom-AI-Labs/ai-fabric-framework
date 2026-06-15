package com.ai.fabric.realapps.cloudvector;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class CloudQdrantOpenaiVectorSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudQdrantOpenaiVectorSearchApplication.class, args);
    }
}
