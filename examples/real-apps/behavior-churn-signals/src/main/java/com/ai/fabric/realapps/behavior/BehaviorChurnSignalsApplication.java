package com.ai.fabric.realapps.behavior;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class BehaviorChurnSignalsApplication {

    public static void main(String[] args) {
        SpringApplication.run(BehaviorChurnSignalsApplication.class, args);
    }
}
