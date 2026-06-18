package ai.fabric.relationship.it.api;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.service.ReliableRelationshipQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Minimal REST controller exposing the relationship query engine so that the
 * integration tests can exercise the real HTTP API surface.
 */
@RestController
@RequestMapping("/api/relationship-query")
public class RelationshipQueryController {

    private final ReliableRelationshipQueryService queryService;

    public RelationshipQueryController(ReliableRelationshipQueryService queryService) {
        this.queryService = queryService;
    }

    @PostMapping("/execute")
    public ResponseEntity<RAGResponse> execute(@Validated @RequestBody RelationshipQueryRequest request) {
        List<String> entityTypes = CollectionUtils.isEmpty(request.getEntityTypes())
            ? List.of("document")
            : request.getEntityTypes();

        QueryOptions options = QueryOptions.builder()
            .returnMode(request.getReturnMode() != null ? request.getReturnMode() : ReturnMode.FULL)
            .limit(request.getLimit())
            .build();

        RAGResponse response = queryService.execute(request.getQuery(), entityTypes, options);
        return ResponseEntity.ok(response);
    }
}
