package com.ai.infrastructure.relay;

import com.ai.infrastructure.relay.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class AIFabricRelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AIFabricRelayApplication.class, args);
    }
}

