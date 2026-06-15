package com.ai.fabric.realapps.privacyfirst;

import ai.fabric.annotation.EnableAIInfrastructure;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAIInfrastructure
public class PrivacyFirstCustomerFacingSupportApplication {

    public static void main(String[] args) {
        SpringApplication.run(PrivacyFirstCustomerFacingSupportApplication.class, args);
    }
}
