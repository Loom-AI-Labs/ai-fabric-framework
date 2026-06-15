package ai.fabric.behavior.api;

import ai.fabric.behavior.api.dto.BatchProcessingRequest;
import ai.fabric.behavior.api.dto.BatchProcessingResult;
import ai.fabric.behavior.api.dto.ContinuousProcessingRequest;
import ai.fabric.behavior.api.dto.ContinuousProcessingResponse;
import ai.fabric.behavior.api.dto.ScheduledControlResponse;
import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.service.BehaviorProcessingManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/behavior/processing")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "ai.behavior.processing", name = "api-enabled", havingValue = "true", matchIfMissing = true)
public class BehaviorProcessingController {

    private final BehaviorProcessingManager processingManager;

    @PostMapping("/users/{userId}")
    public ResponseEntity<BehaviorInsights> analyzeUser(@PathVariable String userId) {
        BehaviorInsights result = processingManager.analyzeUser(userId);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/batch")
    public ResponseEntity<BatchProcessingResult> processBatch(@RequestBody(required = false) BatchProcessingRequest request) {
        return ResponseEntity.ok(processingManager.processBatch(request != null ? request : new BatchProcessingRequest()));
    }

    @PostMapping("/continuous")
    public ResponseEntity<ContinuousProcessingResponse> startContinuous(@RequestBody ContinuousProcessingRequest request) {
        return ResponseEntity.ok(processingManager.startContinuous(request != null ? request : new ContinuousProcessingRequest()));
    }

    @PostMapping("/continuous/{jobId}/cancel")
    public ResponseEntity<ContinuousProcessingResponse> cancelContinuous(@PathVariable String jobId) {
        ContinuousProcessingResponse response = processingManager.cancelContinuous(jobId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/scheduled/pause")
    public ResponseEntity<ScheduledControlResponse> pauseScheduledProcessing() {
        return ResponseEntity.ok(processingManager.pauseScheduled());
    }

    @PostMapping("/scheduled/resume")
    public ResponseEntity<ScheduledControlResponse> resumeScheduledProcessing() {
        return ResponseEntity.ok(processingManager.resumeScheduled());
    }
}
