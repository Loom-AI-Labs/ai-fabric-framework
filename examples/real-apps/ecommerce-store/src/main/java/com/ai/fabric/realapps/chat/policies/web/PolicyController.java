package com.ai.fabric.realapps.chat.policies.web;

import com.ai.fabric.realapps.chat.policies.domain.Policy;
import com.ai.fabric.realapps.chat.policies.service.PolicyService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policies")
@RequiredArgsConstructor
public class PolicyController {

    private final PolicyService policyService;

    @GetMapping("/count")
    public Map<String, Object> count() {
        return Map.of("totalPolicies", policyService.count());
    }

    @GetMapping
    public List<Policy> list(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        return policyService.list(limit);
    }

    @GetMapping("/{id}")
    public Policy get(@PathVariable long id) {
        return policyService.get(id);
    }

    @GetMapping("/search")
    public List<Policy> search(@RequestParam("q") String query,
                               @RequestParam(value = "limit", defaultValue = "10") int limit,
                               @RequestParam(value = "threshold", defaultValue = "0.3") double threshold) {
        return policyService.search(query, limit, threshold);
    }

    @PostMapping
    public ResponseEntity<Policy> create(@Valid @RequestBody CreatePolicyRequest request) {
        Policy created = policyService.createPolicy(
            request.getTitle(),
            request.getText(),
            request.getClassification()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public Policy update(@PathVariable long id, @Valid @RequestBody UpdatePolicyRequest request) {
        return policyService.updatePolicy(
            id,
            request.getTitle(),
            request.getText(),
            request.getClassification()
        );
    }

    @DeleteMapping("/{id}")
    public Policy delete(@PathVariable long id) {
        return policyService.deletePolicy(id);
    }

    @Data
    public static class CreatePolicyRequest {
        @NotBlank
        private String title;
        @NotBlank
        private String text;
        @NotBlank
        private String classification;
    }

    @Data
    public static class UpdatePolicyRequest {
        private String title;
        private String text;
        private String classification;
    }
}
