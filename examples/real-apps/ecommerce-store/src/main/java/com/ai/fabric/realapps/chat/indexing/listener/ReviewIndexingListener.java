package com.ai.fabric.realapps.chat.indexing.listener;

import com.ai.fabric.realapps.chat.indexing.client.RestConnectorDataSyncClient;
import com.ai.fabric.realapps.chat.indexing.events.ReviewDeleteIndexingEvent;
import com.ai.fabric.realapps.chat.indexing.events.ReviewUpsertIndexingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connector.indexing", name = "enabled", havingValue = "true")
public class ReviewIndexingListener {

    private final RestConnectorDataSyncClient dataSyncClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewUpsert(ReviewUpsertIndexingEvent event) {
        if (event == null) {
            return;
        }
        if (!StringUtils.hasText(event.sku())) {
            log.warn("Skipping review indexing for reviewId={} because sku is blank", event.reviewId());
            return;
        }

        String id = externalId(event.reviewId());
        try {
            dataSyncClient.upsertReview(id, toEntity(event));
        } catch (Exception ex) {
            log.warn("Review upsert indexing failed for id={}: {}", id, ex.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewDelete(ReviewDeleteIndexingEvent event) {
        if (event == null) {
            return;
        }
        String id = externalId(event.reviewId());
        try {
            dataSyncClient.deleteReview(id);
        } catch (Exception ex) {
            log.warn("Review delete indexing failed for id={}: {}", id, ex.getMessage());
        }
    }

    private Map<String, Object> toEntity(ReviewUpsertIndexingEvent event) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("userId", event.userId());
        entity.put("sku", event.sku());
        entity.put("rating", event.rating());
        entity.put("text", event.text());
        return entity;
    }

    private String externalId(long reviewId) {
        return "review-" + reviewId;
    }
}
