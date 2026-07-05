package com.ai.fabric.realapps.chat.demo.web;

import com.ai.fabric.realapps.chat.demo.service.DemoReadinessService;
import com.ai.fabric.realapps.chat.demo.service.DemoStageSeedService;
import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoController.class)
@TestPropertySource(properties = {
    "app.demo.controls.enabled=true",
    "app.demo.controls.api-key=",
    "app.build-info.path=classpath:build-info-test.properties",
    "APP_BUILD_COMMIT=stale-env-commit",
    "APP_BUILD_BRANCH=stale-env-branch",
    "APP_BUILD_TIME=2026-01-01T00:00:00Z"
})
class DemoControllerBuildInfoFileTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoReadinessService readinessService;

    @MockitoBean
    private DemoStageSeedService stageSeedService;

    @MockitoBean
    private DemoDataResetService resetService;

    @Test
    void healthPrefersBakedBuildInfoOverRuntimeEnvironment() throws Exception {
        when(readinessService.readiness()).thenReturn(readiness());

        mockMvc.perform(get("/api/demo/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.commit").value("file-commit"))
            .andExpect(jsonPath("$.buildBranch").value("file-branch"))
            .andExpect(jsonPath("$.buildTime").value("2026-07-05T21:00:00Z"))
            .andExpect(jsonPath("$.buildMetadataSource").value("classpath:build-info-test.properties"));
    }

    private DemoReadinessService.ReadinessReport readiness() {
        return DemoReadinessService.ReadinessReport.builder()
            .ready(true)
            .stage("full")
            .stageNumber(5)
            .counts(Map.of())
            .vectorSpaces(Map.of())
            .indexingQueueSize(0L)
            .nextRecommendedStep("next")
            .warnings(java.util.List.of())
            .checkedAt("2026-07-05T21:00:00Z")
            .build();
    }
}
