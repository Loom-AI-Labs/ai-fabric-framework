package com.ai.fabric.realapps.chat.policies.service;

import com.ai.fabric.realapps.chat.policies.domain.Policy;
import com.ai.fabric.realapps.chat.policies.repo.PolicyRepository;
import ai.fabric.annotation.AIProcess;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final AICoreService aiCoreService;

    @Transactional(readOnly = true)
    public List<Policy> list(int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 500);
        return policyRepository.findAll().stream().limit(effectiveLimit).toList();
    }

    @Transactional(readOnly = true)
    public Policy get(long id) {
        return policyRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Policy not found: " + id));
    }

    @Transactional
    @AIProcess(entityType = "policy", operation = AIProcessOperation.CREATE)
    public Policy createPolicy(String title, String text, String classification) {
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("title is required");
        }
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("text is required");
        }
        if (!StringUtils.hasText(classification)) {
            throw new IllegalArgumentException("classification is required");
        }

        Policy policy = new Policy();
        policy.setTitle(title.trim());
        policy.setText(text.trim());
        policy.setClassification(classification.trim());
        return policyRepository.save(policy);
    }

    @Transactional
    @AIProcess(entityType = "policy", operation = AIProcessOperation.UPDATE)
    public Policy updatePolicy(long id, String title, String text, String classification) {
        Policy policy = get(id);

        if (StringUtils.hasText(title)) {
            policy.setTitle(title.trim());
        }
        if (StringUtils.hasText(text)) {
            policy.setText(text.trim());
        }
        if (classification != null) {
            policy.setClassification(StringUtils.hasText(classification) ? classification.trim() : null);
        }

        if (!StringUtils.hasText(policy.getClassification())) {
            throw new IllegalArgumentException("classification is required");
        }

        return policyRepository.save(policy);
    }

    @Transactional
    @AIProcess(entityType = "policy", operation = AIProcessOperation.DELETE)
    public Policy deletePolicy(long id) {
        Policy policy = get(id);
        policyRepository.delete(policy);
        return policy;
    }

    @Transactional(readOnly = true)
    public long count() {
        return policyRepository.count();
    }

    @Transactional(readOnly = true)
    public List<Policy> search(String query, int limit, double threshold) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        int effectiveLimit = limit <= 0 ? 10 : Math.min(limit, 50);
        double effectiveThreshold = threshold <= 0.0 ? 0.3 : threshold;

        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .query(query)
            .entityType("policy")
            .limit(effectiveLimit)
            .threshold(effectiveThreshold)
            .build());

        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }

        return response.getResults().stream()
            .map(this::extractEntityId)
            .flatMap(Optional::stream)
            .map(this::findPolicyByEntityId)
            .flatMap(Optional::stream)
            .limit(effectiveLimit)
            .toList();
    }

    private Optional<String> extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        return firstPresent(row.get("entityId"), row.get("id"))
            .map(Objects::toString)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> firstPresent(Object first, Object second) {
        return Optional.ofNullable(first != null ? first : second);
    }

    private Optional<Policy> findPolicyByEntityId(String id) {
        try {
            return policyRepository.findById(Long.parseLong(id));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
