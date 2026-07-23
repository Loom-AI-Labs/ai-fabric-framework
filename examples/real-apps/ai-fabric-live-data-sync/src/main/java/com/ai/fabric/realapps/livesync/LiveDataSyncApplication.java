package com.ai.fabric.realapps.livesync;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAIInfrastructure
@EnableScheduling
@SpringBootApplication
public class LiveDataSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiveDataSyncApplication.class, args);
    }
}
