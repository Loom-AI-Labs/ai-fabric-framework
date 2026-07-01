package com.ai.fabric.realapps.dbactionregistry;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class DbActionRegistryLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbActionRegistryLabApplication.class, args);
    }
}
