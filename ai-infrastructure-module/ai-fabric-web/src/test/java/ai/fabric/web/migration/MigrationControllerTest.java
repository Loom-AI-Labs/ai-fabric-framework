package ai.fabric.web.migration;

import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationProgress;
import ai.fabric.migration.domain.MigrationStatus;
import ai.fabric.migration.service.DataMigrationService;
import ai.fabric.web.TestWebApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestWebApplication.class, properties = "ai.enabled=false")
@AutoConfigureMockMvc
class MigrationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataMigrationService migrationService;

    @Test
    void startMigration_returnsJob() throws Exception {
        MigrationJob job = MigrationJob.builder()
            .id("mig-1")
            .entityType("product")
            .status(MigrationStatus.RUNNING)
            .batchSize(500)
            .processedEntities(0L)
            .totalEntities(1000L)
            .startedAt(LocalDateTime.parse("2024-01-01T00:00:00"))
            .build();
        given(migrationService.startMigration(any())).willReturn(job);

        String body = """
            {"entityType":"product","batchSize":500,"reindexExisting":false}
            """;

        mockMvc.perform(post("/api/ai/migration/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id", is("mig-1")))
            .andExpect(jsonPath("$.entityType", is("product")))
            .andExpect(jsonPath("$.status", is("RUNNING")));
    }

    @Test
    void listJobs_returnsJobs() throws Exception {
        MigrationJob job = MigrationJob.builder()
            .id("mig-2")
            .entityType("product")
            .status(MigrationStatus.COMPLETED)
            .processedEntities(10L)
            .totalEntities(10L)
            .build();
        given(migrationService.listJobs()).willReturn(List.of(job));

        mockMvc.perform(get("/api/ai/migration/jobs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id", is("mig-2")))
            .andExpect(jsonPath("$[0].status", is("COMPLETED")));
    }

    @Test
    void getProgress_returnsProgress() throws Exception {
        MigrationProgress progress = MigrationProgress.builder()
            .jobId("mig-3")
            .status(MigrationStatus.RUNNING)
            .total(100)
            .processed(25)
            .failed(1)
            .percentComplete(25.0)
            .estimatedTimeRemaining(Duration.ofMinutes(10))
            .build();
        given(migrationService.getProgress("mig-3")).willReturn(progress);

        mockMvc.perform(get("/api/ai/migration/jobs/mig-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId", is("mig-3")))
            .andExpect(jsonPath("$.status", is("RUNNING")))
            .andExpect(jsonPath("$.processed", is(25)));
    }
}
