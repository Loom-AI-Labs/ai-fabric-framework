package com.ai.infrastructure.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.dto.AIDataPrivacyRequest;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateKey;
import com.ai.infrastructure.prompt.PromptTemplate;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.prompt.ResolvedPromptTemplate;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

class AIDataPrivacyServiceTest {

    @Test
    void processDataPrivacyRequestUsesCanonicalSubjectId() {
        AICoreService aiCoreService = mock(AICoreService.class);
        PromptTemplateResolver promptTemplateResolver = mock(PromptTemplateResolver.class);
        PromptRenderer promptRenderer = mock(PromptRenderer.class);
        PromptTemplate promptTemplate =
            new PromptTemplate(new PromptTemplateKey("governance/data-privacy", "v1", "classify-sensitivity"), "Classify {{content}}");
        when(promptTemplateResolver.resolve("governance/data-privacy", "classify-sensitivity"))
            .thenReturn(new ResolvedPromptTemplate(
                promptTemplate,
                List.of("v1")
            ));
        when(promptRenderer.render(promptTemplate, Map.of("content", "short content")))
            .thenReturn("Classify short content");
        when(aiCoreService.generateText("Classify short content")).thenReturn("PUBLIC");

        AIDataPrivacyService service = new AIDataPrivacyService(
            aiCoreService,
            promptTemplateResolver,
            promptRenderer
        );

        AIDataPrivacyRequest request = AIDataPrivacyRequest.builder()
            .requestId("privacy-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("customer-42")
                .sessionId("session-42")
                .subjectType("END_USER")
                .build())
            .content("short content")
            .purpose("support")
            .consentGiven(true)
            .consentRequired(false)
            .build();

        var response = service.processDataPrivacyRequest(request);

        assertThat(response.getSubjectId()).isEqualTo("customer-42");
        assertThat(response.getSuccess()).isTrue();
    }
}
