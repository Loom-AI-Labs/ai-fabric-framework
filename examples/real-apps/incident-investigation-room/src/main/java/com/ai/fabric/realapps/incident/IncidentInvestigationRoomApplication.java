package com.ai.fabric.realapps.incident;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@EnableAIInfrastructure
@SpringBootApplication
public class IncidentInvestigationRoomApplication {

    public static void main(String[] args) {
        SpringApplication.run(IncidentInvestigationRoomApplication.class, args);
    }
}
