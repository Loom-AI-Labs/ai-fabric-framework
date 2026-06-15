package ai.fabric.relationship.integration;

import ai.fabric.entity.IntentHistory;
import ai.fabric.provider.onnx.ONNXAutoConfiguration;
import ai.fabric.relationship.config.RelationshipQueryAutoConfiguration;
import ai.fabric.relationship.integration.entity.DocumentEntity;
import ai.fabric.relationship.integration.repository.DocumentRepository;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.vector.lucene.LuceneVectorAutoConfiguration;
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

