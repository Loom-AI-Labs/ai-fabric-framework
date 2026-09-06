package com.ai.fabric.realapps.behavior.web;

import com.ai.fabric.realapps.behavior.service.AgenticUiComposerService;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService;
import com.ai.fabric.realapps.behavior.service.DurableBehaviorAnalysisService;
import ai.fabric.execution.specialist.SpecialistRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorDemoControllerTest {

    private final BehaviorDemoScenarioService service = mock(BehaviorDemoScenarioService.class);
    private final AgenticUiComposerService agenticUiComposerService = mock(AgenticUiComposerService.class);
    private final DurableBehaviorAnalysisService durableAnalysisService = mock(DurableBehaviorAnalysisService.class);
    private final SpecialistRegistry specialistRegistry = mock(SpecialistRegistry.class);

    @Test
    void healthReportsBuildMetadataAndProviderPosture() {
        when(service.dashboard()).thenReturn(new BehaviorDemoScenarioService.BehaviorDemoDashboard(
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            List.of(),
            33
        ));

        MockEnvironment environment = new MockEnvironment()
            .withProperty("ai.providers.llm-provider", "behavior-local")
            .withProperty("ai.behavior.enabled", "true")
            .withProperty("ai.behavior.mode", "FULL");
        BehaviorDemoController controller = new BehaviorDemoController(
            service,
            agenticUiComposerService,
            durableAnalysisService,
            specialistRegistry,
            environment,
            new DefaultResourceLoader()
        );
        ReflectionTestUtils.setField(controller, "appName", "behavior-churn-signals");
        ReflectionTestUtils.setField(controller, "appVersion", "1.0.0-test");
        ReflectionTestUtils.setField(controller, "aiFabricVersion", "0.3.3-test");
        ReflectionTestUtils.setField(controller, "buildInfoPath", "classpath:/behavior-build-info-test.properties");

        Map<String, Object> health = controller.health();

        assertThat(health).containsEntry("app", "behavior-churn-signals");
        assertThat(health).containsEntry("version", "1.2.3");
        assertThat(health).containsEntry("aiFabricVersion", "0.3.3");
        assertThat(health).containsEntry("commit", "abc1234");
        assertThat(health).containsEntry("buildBranch", "main");
        assertThat(health).containsEntry("provider", "behavior-local");
        assertThat(health).containsEntry("providerMode", "deterministic-local");
        assertThat(health).containsEntry("behaviorEnabled", true);
        assertThat(health).containsEntry("behaviorMode", "FULL");
        assertThat(health).containsEntry("totalEvents", 33L);
        assertThat(health).containsEntry("scenarios", 0);
        assertThat(health).containsEntry(
            "executionSources",
            List.of("APPLICATION", "SCHEDULED")
        );
    }

    @Test
    void agenticUiUsesTheLatestApprovedScenarioInsight() {
        BehaviorDemoScenarioService.BehaviorScenarioResult scenarioResult =
            new BehaviorDemoScenarioService.BehaviorScenarioResult(null, null, List.of(), null, null);
        AgenticUiComposerService.AgenticUiResponse agenticResponse =
            new AgenticUiComposerService.AgenticUiResponse("user-1001", "acct-1001", "Acme Finance", null, null, null);
        when(service.currentResult("user-1001")).thenReturn(scenarioResult);
        when(agenticUiComposerService.compose(scenarioResult)).thenReturn(agenticResponse);

        MockEnvironment environment = new MockEnvironment();
        BehaviorDemoController controller = new BehaviorDemoController(
            service,
            agenticUiComposerService,
            durableAnalysisService,
            specialistRegistry,
            environment,
            new DefaultResourceLoader()
        );

        AgenticUiComposerService.AgenticUiResponse response = controller.agenticUi("user-1001");

        assertThat(response).isSameAs(agenticResponse);
        verify(service).currentResult("user-1001");
        verify(agenticUiComposerService).compose(scenarioResult);
    }

    @Test
    void scheduledAnalysisUsesTheServerOwnedScheduledAdapter() {
        DurableBehaviorAnalysisService.AnalysisView expected = mock(
            DurableBehaviorAnalysisService.AnalysisView.class
        );
        when(durableAnalysisService.submitScheduled("session-1", "user-1"))
            .thenReturn(expected);

        BehaviorDemoController controller = new BehaviorDemoController(
            service,
            agenticUiComposerService,
            durableAnalysisService,
            specialistRegistry,
            new MockEnvironment(),
            new DefaultResourceLoader()
        );

        assertThat(
            controller.submitScheduledAnalysis("user-1", "session-1")
        ).isSameAs(expected);
        verify(durableAnalysisService).submitScheduled("session-1", "user-1");
    }
}
