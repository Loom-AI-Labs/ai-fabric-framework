package com.ai.fabric.realapps.chat;

import com.ai.infrastructure.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class ChatCapabilitiesDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatCapabilitiesDemoApplication.class, args);
    }
}
