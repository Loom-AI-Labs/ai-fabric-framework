package com.ai.fabric.realapps.privacyfirst.service;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.privacy.pii.PIIDetectionService;
import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SupportMessageProjectionTest {

    @Test
    void approvedProjectionExcludesEncryptedOriginalsAndEncryptionSalts() {
        PIIDetectionService pii = mock(PIIDetectionService.class);
        when(pii.detectAndProcess(anyString())).thenAnswer(invocation ->
            PIIDetectionResult.builder()
                .processedQuery(invocation.getArgument(0))
                .piiDetected(false)
                .build()
        );
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("pii", pii);
        AIEntityConfigurationLoader loader =
            new AIEntityConfigurationLoader(new MockEnvironment());
        loader.loadConfiguration();
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AIEntityDescriptorRegistry registry = new AIEntityDescriptorRegistry(
            loader,
            List.of(),
            List.of(),
            beans.getBeanProvider(PIIDetectionService.class),
            objectMapper
        );
        AIEntityProjectionService projectionService =
            new AIEntityProjectionService(
                registry,
                beans.getBeanProvider(PIIDetectionService.class),
                objectMapper,
                Clock.systemUTC()
            );
        SupportMessage message = new SupportMessage();
        message.setId(42L);
        message.setCustomerId("customer-1");
        message.setChannel("webchat");
        message.setProcessedSubject("Billing for [EMAIL]");
        message.setProcessedMessage("Please update [PHONE]");
        message.setSubjectEncryptedOriginal("encrypted-subject-secret");
        message.setSubjectEncryptionSalt("subject-salt-secret");
        message.setMessageEncryptedOriginal("encrypted-message-secret");
        message.setMessageEncryptionSalt("message-salt-secret");

        var document = projectionService.project(
            message,
            AIProcessOperation.CREATE,
            "privacy-test"
        );
        String serialized = objectMapper.valueToTree(document).toString();

        assertThat(serialized)
            .doesNotContain("encrypted-subject-secret")
            .doesNotContain("subject-salt-secret")
            .doesNotContain("encrypted-message-secret")
            .doesNotContain("message-salt-secret");
        assertThat(document.semanticSearchText())
            .contains("processedMessage: Please update [PHONE]");
    }
}
