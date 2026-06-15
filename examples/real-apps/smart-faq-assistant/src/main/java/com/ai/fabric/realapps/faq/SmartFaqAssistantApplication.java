package com.ai.fabric.realapps.faq;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class SmartFaqAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartFaqAssistantApplication.class, args);
    }
}
