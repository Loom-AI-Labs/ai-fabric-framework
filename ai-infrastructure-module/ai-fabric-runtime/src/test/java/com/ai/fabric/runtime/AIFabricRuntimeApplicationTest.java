package com.ai.fabric.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "OPENAI_ENABLED=true",
    "OPENAI_API_KEY=test",
    "ACTIONS_CONNECTOR_BASE_URL=http://localhost:18082",
    "ACTIONS_CONNECTOR_API_KEY=test",
    "spring.datasource.url=jdbc:h2:mem:runtime-context-loads;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.config.default-file=classpath:test-runtime-entity-config.yml",
    "AI_FABRIC_RUNTIME_AUTH_INGRESS_MODE=VERIFIED_CONTEXT_REQUIRED",
    "AI_FABRIC_RUNTIME_TRUSTED_BACKEND_API_KEY=runtime-trusted-backend-secret",
    "AI_FABRIC_RUNTIME_PRIVATE_ASSERTION_SIGNING_KEY=runtime-private-signing-secret",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_ISSUERS=platform-poc:SESSION,platform-poc:API_KEY,platform-poc:SYSTEM",
    "AI_FABRIC_RUNTIME_AUTH_ACCEPTED_AUDIENCES=dep-runtime"
})
class AIFabricRuntimeApplicationTest {

    @Test
    void contextLoads() {
    }
}
