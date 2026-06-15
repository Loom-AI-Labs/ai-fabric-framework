package com.ai.fabric.realapps.crm.web;

import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.service.RelationshipSchemaProvider;
import ai.fabric.relationship.service.ReliableRelationshipQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/crm")
@RequiredArgsConstructor
public class CrmQueryController {

    private final ReliableRelationshipQueryService relationshipQueryService;
    private final RelationshipSchemaProvider schemaProvider;

    @GetMapping("/schema")
    public String schema() {
        return schemaProvider.getSchemaDescription(List.of("account", "contact", "deal", "support-ticket"));
    }

    @PostMapping("/query")
    public RAGResponse query(@Valid @RequestBody QueryRequest request) {
        List<String> entityTypes = request.entityTypes() == null || request.entityTypes().isEmpty()
            ? List.of("account", "contact", "deal", "support-ticket")
            : request.entityTypes();
        return relationshipQueryService.execute(request.query(), entityTypes, null);
    }

    public record QueryRequest(
        @NotBlank String query,
        List<String> entityTypes
    ) {}
}
