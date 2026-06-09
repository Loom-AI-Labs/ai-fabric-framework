package com.ai.fabric.realapps.chat.indexing.listener;

import com.ai.fabric.realapps.chat.indexing.client.RestConnectorDataSyncClient;
import com.ai.fabric.realapps.chat.indexing.events.PolicyDeleteIndexingEvent;
import com.ai.fabric.realapps.chat.indexing.events.PolicyUpsertIndexingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connector.indexing", name = "enabled", havingValue = "true")
public class PolicyIndexingListener {

    private final RestConnectorDataSyncClient dataSyncClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyUpsert(PolicyUpsertIndexingEvent event) {
        if (event == null) {
            return;
        }
        String id = externalId(event.policyId());
        try {
            dataSyncClient.upsertPolicy(id, toEntity(event));
        } catch (Exception ex) {
            log.warn("Policy upsert indexing failed for id={}: {}", id, ex.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPolicyDelete(PolicyDeleteIndexingEvent event) {
        if (event == null) {
            return;
        }
        String id = externalId(event.policyId());
        try {
            dataSyncClient.deletePolicy(id);
        } catch (Exception ex) {
            log.warn("Policy delete indexing failed for id={}: {}", id, ex.getMessage());
        }
    }

    private Map<String, Object> toEntity(PolicyUpsertIndexingEvent event) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("title", event.title());
        entity.put("text", event.text());
        entity.put("classification", event.classification());
        return entity;
    }

    private String externalId(long policyId) {
        return "policy-" + policyId;
    }
}
