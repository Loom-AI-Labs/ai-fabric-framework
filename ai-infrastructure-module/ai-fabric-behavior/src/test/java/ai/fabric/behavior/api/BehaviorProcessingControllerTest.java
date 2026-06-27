package ai.fabric.behavior.api;

import ai.fabric.behavior.api.dto.BatchProcessingRequest;
import ai.fabric.behavior.api.dto.BatchProcessingResult;
import ai.fabric.behavior.api.dto.ContinuousProcessingRequest;
import ai.fabric.behavior.api.dto.ContinuousProcessingResponse;
import ai.fabric.behavior.api.dto.ScheduledControlResponse;
import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.service.BehaviorProcessingManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class BehaviorProcessingControllerTest {

    @Mock
    private BehaviorProcessingManager processingManager;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        BehaviorProcessingController controller = new BehaviorProcessingController(processingManager);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setValidator(validator)
            .build();
    }

    @Test
    void analyzeUserReturnsOk() throws Exception {
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId("user-test-ok")
            .segment("seg")
            .build();
        Mockito.when(processingManager.analyzeUser(any())).thenReturn(insight);

        mockMvc.perform(post("/api/behavior/processing/users/{id}", "user-test-ok"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.segment").value("seg"));
    }

    @Test
    void batchReturnsResultFromManager() throws Exception {
        Mockito.when(processingManager.processBatch(any(BatchProcessingRequest.class)))
            .thenReturn(BatchProcessingResult.builder().processedCount(1).successCount(1).build());

        mockMvc.perform(post("/api/behavior/processing/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxUsers\":5,\"maxDurationMinutes\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.processedCount").value(1));
    }

    @Test
    void batchRejectsInvalidBoundsBeforeManagerCall() throws Exception {
        mockMvc.perform(post("/api/behavior/processing/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"maxUsers\":0,\"maxDurationMinutes\":0,\"delayBetweenUsersMs\":-1}"))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(processingManager);
    }

    @Test
    void continuousStartReturnsJobId() throws Exception {
        Mockito.when(processingManager.startContinuous(any(ContinuousProcessingRequest.class)))
            .thenReturn(ContinuousProcessingResponse.builder().jobId("job-1").status("RUNNING").build());

        mockMvc.perform(post("/api/behavior/processing/continuous")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    @Test
    void continuousRejectsInvalidBoundsBeforeManagerCall() throws Exception {
        mockMvc.perform(post("/api/behavior/processing/continuous")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"usersPerBatch\":0,\"intervalMinutes\":-1,\"maxIterations\":0}"))
            .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(processingManager);
    }

    @Test
    void pauseScheduled() throws Exception {
        Mockito.when(processingManager.pauseScheduled())
            .thenReturn(ScheduledControlResponse.builder().paused(true).message("paused").build());

        mockMvc.perform(post("/api/behavior/processing/scheduled/pause"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paused").value(true));
    }

    @Test
    void analyzeUserReturnsNoContentWhenManagerReturnsNull() throws Exception {
        Mockito.when(processingManager.analyzeUser(any())).thenReturn(null);

        mockMvc.perform(post("/api/behavior/processing/users/{id}", "user-null-test"))
            .andExpect(status().isNoContent());
    }

    @Test
    void cancelNonExistingContinuousJobReturnsNotFound() throws Exception {
        Mockito.when(processingManager.cancelContinuous("missing")).thenReturn(null);

        mockMvc.perform(post("/api/behavior/processing/continuous/{jobId}/cancel", "missing"))
            .andExpect(status().isNotFound());
    }

    @Test
    void invalidJsonBodyReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/behavior/processing/batch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{invalid-json"))
            .andExpect(status().isBadRequest());
    }
}
