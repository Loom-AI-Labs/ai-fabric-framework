package com.ai.fabric.examples.minimal;

import com.ai.infrastructure.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class MinimalApplication {
    public static void main(String[] args) {
        SpringApplication.run(MinimalApplication.class, args);
    }
}
