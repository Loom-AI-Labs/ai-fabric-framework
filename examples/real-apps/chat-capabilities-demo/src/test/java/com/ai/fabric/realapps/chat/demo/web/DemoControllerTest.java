package com.ai.fabric.realapps.chat.demo.web;

import com.ai.fabric.realapps.chat.demo.service.DemoReadinessService;
import com.ai.fabric.realapps.chat.demo.service.DemoStage;
import com.ai.fabric.realapps.chat.demo.service.DemoStageSeedService;
import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoController.class)
@TestPropertySource(properties = {
    "app.demo.controls.enabled=true",
    "app.demo.controls.api-key="
})
class DemoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoReadinessService readinessService;

    @MockitoBean
    private DemoStageSeedService stageSeedService;

    @MockitoBean
    private DemoDataResetService resetService;

    @Test
    void readinessReturnsCurrentStage() throws Exception {
        when(readinessService.readiness()).thenReturn(readiness("products"));

        mockMvc.perform(get("/api/demo/readiness"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.stage").value("products"))
            .andExpect(jsonPath("$.counts.products").value(100));
    }

    @Test
    void seedStageDelegatesToSeedService() throws Exception {
        when(stageSeedService.seed(DemoStage.REVIEWS)).thenReturn(DemoStageSeedService.StageSeedResult.builder()
            .success(true)
            .stage("reviews")
            .results(Map.of("reviews", Map.of("created", 200)))
            .readiness(readiness("reviews"))
            .build());

        mockMvc.perform(post("/api/demo/stages/reviews"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.stage").value("reviews"));

        verify(stageSeedService).seed(DemoStage.REVIEWS);
    }

    @Test
    void resetRequiresConfirmation() throws Exception {
        mockMvc.perform(post("/api/demo/reset")
                .contentType("application/json")
                .content("{\"confirm\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false));
    }

    private DemoReadinessService.ReadinessReport readiness(String stage) {
        return DemoReadinessService.ReadinessReport.builder()
            .ready("full".equals(stage))
            .stage(stage)
            .stageNumber(1)
            .counts(Map.of(
                "products", 100L,
                "reviews", 0L,
                "policies", 0L,
                "coupons", 0L,
                "tickets", 0L
            ))
            .vectorSpaces(Map.of())
            .indexingQueueSize(0L)
            .nextRecommendedStep("next")
            .warnings(java.util.List.of())
            .checkedAt("2026-07-04T00:00:00Z")
            .build();
    }
}
