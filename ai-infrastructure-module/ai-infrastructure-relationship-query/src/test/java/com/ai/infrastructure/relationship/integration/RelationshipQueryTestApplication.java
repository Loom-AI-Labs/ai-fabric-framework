package com.ai.infrastructure.relationship.integration;

import com.ai.infrastructure.entity.IntentHistory;
import com.ai.infrastructure.provider.onnx.ONNXAutoConfiguration;
import com.ai.infrastructure.relationship.config.RelationshipQueryAutoConfiguration;
import com.ai.infrastructure.relationship.integration.entity.DocumentEntity;
import com.ai.infrastructure.relationship.integration.repository.DocumentRepository;
import com.ai.infrastructure.repository.IntentHistoryRepository;
import com.ai.infrastructure.vector.lucene.LuceneVectorAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
    ONNXAutoConfiguration.class,
    LuceneVectorAutoConfiguration.class
})
@EntityScan(basePackageClasses = {
    DocumentEntity.class,
    IntentHistory.class
})
@EnableJpaRepositories(basePackageClasses = {
    DocumentRepository.class,
    IntentHistoryRepository.class
})
@Import({
    RelationshipQueryIntegrationTestBeans.class,
    RelationshipQueryAutoConfiguration.class
})
public class RelationshipQueryTestApplication {}

