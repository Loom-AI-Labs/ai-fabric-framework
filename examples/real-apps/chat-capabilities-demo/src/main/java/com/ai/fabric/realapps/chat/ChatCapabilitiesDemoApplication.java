package com.ai.fabric.realapps.chat;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAIInfrastructure
@EnableScheduling
public class ChatCapabilitiesDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatCapabilitiesDemoApplication.class, args);
    }
}
