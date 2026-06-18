package ai.fabric.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.AIContentFilterRequest;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplate;
import ai.fabric.prompt.PromptTemplateKey;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.prompt.ResolvedPromptTemplate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AIContentFilterServiceTest {

    @Test
    void filterContentUsesCanonicalSubjectId() {
        AICoreService aiCoreService = mock(AICoreService.class);
        PromptTemplateResolver promptTemplateResolver = mock(PromptTemplateResolver.class);
        PromptRenderer promptRenderer = mock(PromptRenderer.class);
        PromptTemplate promptTemplate =
            new PromptTemplate(new PromptTemplateKey("governance/content-filter", "v1", "analyze-violations"), "Analyze {{content}}");
        when(promptTemplateResolver.resolve("governance/content-filter", "analyze-violations"))
            .thenReturn(new ResolvedPromptTemplate(
                promptTemplate,
                List.of("v1")
            ));
        when(promptRenderer.render(promptTemplate, Map.of("content", "safe content")))
            .thenReturn("Analyze safe content");
        when(aiCoreService.generateText("Analyze safe content")).thenReturn("NONE");

        AIContentFilterService service = new AIContentFilterService(
            aiCoreService,
            promptTemplateResolver,
            promptRenderer
        );

        AIContentFilterRequest request = AIContentFilterRequest.builder()
            .requestId("filter-1")
            .authContext(AIAccessSubjectContext.builder()
                .subjectId("customer-7")
                .sessionId("session-7")
                .subjectType("END_USER")
                .build())
            .content("safe content")
            .build();

        var response = service.filterContent(request);

        assertThat(response.getSubjectId()).isEqualTo("customer-7");
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getShouldFilter()).isFalse();
    }

    @Test
    void filterContentTreatsNoneViolationCaseInsensitively() {
        AICoreService aiCoreService = mock(AICoreService.class);
        PromptTemplateResolver promptTemplateResolver = mock(PromptTemplateResolver.class);
        PromptRenderer promptRenderer = mock(PromptRenderer.class);
        PromptTemplate promptTemplate =
            new PromptTemplate(new PromptTemplateKey("governance/content-filter", "v1", "analyze-violations"), "Analyze {{content}}");
        when(promptTemplateResolver.resolve("governance/content-filter", "analyze-violations"))
            .thenReturn(new ResolvedPromptTemplate(
                promptTemplate,
                List.of("v1")
            ));
        when(promptRenderer.render(promptTemplate, Map.of("content", "safe content")))
            .thenReturn("Analyze safe content");
        when(aiCoreService.generateText("Analyze safe content")).thenReturn(" none \n");

        AIContentFilterService service = new AIContentFilterService(
            aiCoreService,
            promptTemplateResolver,
            promptRenderer
        );

        AIContentFilterRequest request = AIContentFilterRequest.builder()
            .requestId("filter-none")
            .content("safe content")
            .build();

        var response = service.filterContent(request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getViolations()).isEmpty();
        assertThat(response.getShouldFilter()).isFalse();
    }

    @Test
    void filterContentRejectsNullRequest() {
        AIContentFilterService service = new AIContentFilterService(
            mock(AICoreService.class),
            mock(PromptTemplateResolver.class),
            mock(PromptRenderer.class)
        );

        assertThatThrownBy(() -> service.filterContent(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("content filter request");
    }
}
