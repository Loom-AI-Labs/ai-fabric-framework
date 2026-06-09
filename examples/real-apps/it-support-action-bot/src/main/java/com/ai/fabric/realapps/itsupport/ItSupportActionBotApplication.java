package com.ai.fabric.realapps.itsupport;

import com.ai.infrastructure.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class ItSupportActionBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(ItSupportActionBotApplication.class, args);
    }
}
