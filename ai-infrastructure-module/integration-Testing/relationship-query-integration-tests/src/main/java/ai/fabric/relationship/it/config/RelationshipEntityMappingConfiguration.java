package ai.fabric.relationship.it.config;

import ai.fabric.relationship.it.entity.AccountEntity;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.DocumentEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.entity.TransactionEntity;
import ai.fabric.relationship.it.entity.UserEntity;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.relationship.dto.RelationshipDirection;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the test entities and relationships with the mapper so that the schema
 * exposed to the planner is deterministic.
 */
@Slf4j
@Configuration
public class RelationshipEntityMappingConfiguration {

    @Bean
    public InitializingBean relationshipEntityInitializer(EntityRelationshipMapper mapper) {
        return () -> {
            mapper.registerEntityType(DocumentEntity.class);
            mapper.registerEntityType(UserEntity.class);
            mapper.registerRelationship("document", "user", "author", RelationshipDirection.FORWARD, false);

            mapper.registerEntityType(ProductEntity.class);
            mapper.registerEntityType(BrandEntity.class);
            mapper.registerRelationship("product", "brand", "brand", RelationshipDirection.FORWARD, false);

            mapper.registerEntityType(TransactionEntity.class);
            mapper.registerEntityType(AccountEntity.class);

            mapper.registerEntityType("destination-account", AccountEntity.class);
            mapper.registerEntityType("origin-account", AccountEntity.class);
            mapper.registerRelationship("transaction", "destination-account", "destinationAccount", RelationshipDirection.FORWARD, false);
            mapper.registerRelationship("transaction", "origin-account", "sourceAccount", RelationshipDirection.FORWARD, false);

            log.info("Registered relationship-test entity schema: {} entities, {} relationships",
                mapper.getAllEntityMappings().size(),
                mapper.getAllRelationshipMappings().size());
        };
    }

}
