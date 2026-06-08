package com.ai.infrastructure.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * API Specification DTO
 * 
 * Represents a complete API specification containing endpoints, metadata,
 * and documentation for AI-generated APIs.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class APISpecification {
    
    /**
     * API title
     */
    private String title;
    
    /**
     * API version
     */
    private String version;
    
    /**
     * API description
     */
    private String description;
    
    /**
     * API base URL
     */
    private String baseUrl;
    
    /**
     * API host
     */
    private String host;
    
    /**
     * API schemes (http, https)
     */
    private List<String> schemes;
    
    /**
     * API consumes (content types)
     */
    private List<String> consumes;
    
    /**
     * API produces (content types)
     */
    private List<String> produces;
    
    /**
     * API tags
     */
    private List<String> tags;
    
    /**
     * API endpoints
     */
    private List<APIEndpointDefinition> endpoints;
    
    /**
     * API metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * API contact information
     */
    private ContactInfo contact;
    
    /**
     * API license information
     */
    private LicenseInfo license;
    
    /**
     * API terms of service
     */
    private String termsOfService;
    
    /**
     * API external documentation
     */
    private ExternalDocumentation externalDocs;
    
    /**
     * API security definitions
     */
    private Map<String, SecurityDefinition> securityDefinitions;
    
    /**
     * API global security requirements
     */
    private List<SecurityRequirement> security;
    
    /**
     * API parameters
     */
    private Map<String, ParameterDefinition> parameters;
    
    /**
     * API responses
     */
    private Map<String, ResponseDefinition> responses;
    
    /**
     * API definitions (data models)
     */
    private Map<String, SchemaDefinition> definitions;
    
    /**
     * API paths
     */
    private Map<String, PathDefinition> paths;
    
    /**
     * API servers
     */
    private List<ServerDefinition> servers;
    
    /**
     * API components
     */
    private ComponentsDefinition components;
    
    /**
     * API info
     */
    private InfoDefinition info;
    
    /**
     * When the specification was generated
     */
    private LocalDateTime generatedAt;
    
    /**
     * When the specification was last updated
     */
    private LocalDateTime lastUpdated;
    
    /**
     * Specification generation source
     */
    private String generatedBy;
    
    /**
     * Specification generation version
     */
    private String generatorVersion;
    
    /**
     * Whether this specification is valid
     */
    private Boolean valid;
    
    /**
     * Validation errors if any
     */
    private List<String> validationErrors;
    
    /**
     * Specification statistics
     */
    private Map<String, Object> statistics;
    
    /**
     * Contact Information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContactInfo {
        private String name;
        private String url;
        private String email;
    }
    
    /**
     * License Information
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LicenseInfo {
        private String name;
        private String url;
    }
    
    /**
     * External Documentation
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExternalDocumentation {
        private String description;
        private String url;
    }
    
    /**
     * Security Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityDefinition {
        private String type;
        private String description;
        private String name;
        private String in;
        private String scheme;
        private String bearerFormat;
        private Map<String, Object> flows;
        private String openIdConnectUrl;
    }
    
    /**
     * Security Requirement
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityRequirement {
        private String name;
        private List<String> scopes;
    }
    
    /**
     * Parameter Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterDefinition {
        private String name;
        private String in;
        private String description;
        private Boolean required;
        private Boolean deprecated;
        private Boolean allowEmptyValue;
        private String style;
        private Boolean explode;
        private Boolean allowReserved;
        private SchemaDefinition schema;
        private Object example;
        private Map<String, Object> examples;
    }
    
    /**
     * Response Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseDefinition {
        private String description;
        private Map<String, HeaderDefinition> headers;
        private Map<String, SchemaDefinition> content;
        private Map<String, Object> links;
    }
    
    /**
     * Header Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HeaderDefinition {
        private String description;
        private Boolean required;
        private Boolean deprecated;
        private Boolean allowEmptyValue;
        private String style;
        private Boolean explode;
        private SchemaDefinition schema;
        private Object example;
    }
    
    /**
     * Schema Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemaDefinition {
        private String type;
        private String format;
        private String description;
        private Object example;
        private Map<String, Object> examples;
        private Boolean nullable;
        private Boolean readOnly;
        private Boolean writeOnly;
        private Boolean deprecated;
        private String discriminator;
        private String xml;
        private Map<String, SchemaDefinition> properties;
        private List<String> required;
        private List<Object> enumValues;
        private Object defaultValue;
        private List<SchemaDefinition> allOf;
        private List<SchemaDefinition> oneOf;
        private List<SchemaDefinition> anyOf;
        private SchemaDefinition not;
        private List<SchemaDefinition> items;
        private Integer minItems;
        private Integer maxItems;
        private Boolean uniqueItems;
        private Integer minLength;
        private Integer maxLength;
        private String pattern;
        private Number minimum;
        private Number maximum;
        private Boolean exclusiveMinimum;
        private Boolean exclusiveMaximum;
        private Integer multipleOf;
        private String ref;
    }
    
    /**
     * Path Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathDefinition {
        private String summary;
        private String description;
        private String operationId;
        private List<String> tags;
        private List<String> consumes;
        private List<String> produces;
        private List<ParameterDefinition> parameters;
        private Map<String, ResponseDefinition> responses;
        private List<Map<String, List<String>>> security;
        private Boolean deprecated;
        private List<Map<String, Object>> callbacks;
        private Map<String, Object> extensions;
    }
    
    /**
     * Server Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerDefinition {
        private String url;
        private String description;
        private Map<String, VariableDefinition> variables;
    }
    
    /**
     * Variable Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VariableDefinition {
        private List<String> enumValues;
        private String defaultValue;
        private String description;
    }
    
    /**
     * Components Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComponentsDefinition {
        private Map<String, SchemaDefinition> schemas;
        private Map<String, ResponseDefinition> responses;
        private Map<String, ParameterDefinition> parameters;
        private Map<String, SchemaDefinition> examples;
        private Map<String, SchemaDefinition> requestBodies;
        private Map<String, HeaderDefinition> headers;
        private Map<String, SecurityDefinition> securitySchemes;
        private Map<String, SchemaDefinition> links;
        private Map<String, SchemaDefinition> callbacks;
    }
    
    /**
     * Info Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InfoDefinition {
        private String title;
        private String description;
        private String termsOfService;
        private ContactInfo contact;
        private LicenseInfo license;
        private String version;
    }
}