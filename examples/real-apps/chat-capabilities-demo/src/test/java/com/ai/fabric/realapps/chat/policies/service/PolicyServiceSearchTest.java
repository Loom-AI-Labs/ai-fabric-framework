package com.ai.fabric.realapps.chat.policies.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import com.ai.fabric.realapps.chat.policies.domain.Policy;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyServiceSearchTest {

    private final PolicyRepository policyRepository = mock(PolicyRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final PolicyService service = new PolicyService(policyRepository, aiCoreService);

    @Test
    void searchHydratesValidResultIdsAndSkipsMalformedRows() {
        Policy first = policy(1L);
        Policy second = policy(2L);
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", "1"),
                Map.of("id", "bad"),
                Map.of(),
                Map.of("id", 2L)
            ))
            .build());
        when(policyRepository.findById(1L)).thenReturn(Optional.of(first));
        when(policyRepository.findById(2L)).thenReturn(Optional.of(second));

        List<Policy> results = service.search("refund", 10, 0.2d);

        assertThat(results).extracting(Policy::getId).containsExactly(1L, 2L);
    }

    private static Policy policy(long id) {
        Policy policy = new Policy();
        policy.setId(id);
        policy.setTitle("Policy " + id);
        policy.setText("Policy text");
        policy.setClassification("support");
        return policy;
    }
}
