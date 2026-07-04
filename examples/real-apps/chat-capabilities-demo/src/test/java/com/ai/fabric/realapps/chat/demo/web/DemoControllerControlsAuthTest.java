package com.ai.fabric.realapps.chat.demo.web;

import com.ai.fabric.realapps.chat.demo.service.DemoReadinessService;
import com.ai.fabric.realapps.chat.demo.service.DemoStageSeedService;
import com.ai.fabric.realapps.chat.migration.service.DemoDataResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DemoController.class)
@TestPropertySource(properties = {
    "app.demo.controls.enabled=true",
    "app.demo.controls.api-key=test-key",
    "app.demo.controls.api-key-header=X-DEMO-API-KEY"
})
class DemoControllerControlsAuthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DemoReadinessService readinessService;

    @MockitoBean
    private DemoStageSeedService stageSeedService;

    @MockitoBean
    private DemoDataResetService resetService;

    @Test
    void seedStageRequiresDemoApiKeyWhenConfigured() throws Exception {
        mockMvc.perform(post("/api/demo/stages/full"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void resetRequiresDemoApiKeyWhenConfigured() throws Exception {
        mockMvc.perform(post("/api/demo/reset")
                .contentType("application/json")
                .content("{\"confirm\":true}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false));
    }
}
