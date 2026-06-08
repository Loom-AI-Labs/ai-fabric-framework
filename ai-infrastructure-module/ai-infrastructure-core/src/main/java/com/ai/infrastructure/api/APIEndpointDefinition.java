package com.ai.infrastructure.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * API Endpoint Definition DTO
 * 
 * Represents a single API endpoint with its configuration, request/response schemas,
 * validation rules, and security policies.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class APIEndpointDefinition {
    
    /**
     * Unique endpoint ID
     */
    private String id;
    
    /**
     * Endpoint path (e.g., "/api/products/{id}")
     */
    private String path;
    
    /**
     * HTTP method (GET, POST, PUT, DELETE, etc.)
     */
    private String method;
    
    /**
     * Endpoint operation ID
     */
    private String operationId;
    
    /**
     * Endpoint summary
     */
    private String summary;
    
    /**
     * Endpoint description
     */
    private String description;
    
    /**
     * Endpoint tags
     */
    private List<String> tags;
    
    /**
     * Endpoint category
     */
    private String category;
    
    /**
     * Endpoint priority (1-10, higher = more priority)
     */
    private Integer priority;
    
    /**
     * Whether the endpoint is enabled
     */
    private Boolean enabled;
    
    /**
     * Whether the endpoint is deprecated
     */
    private Boolean deprecated;
    
    /**
     * Request schema definition
     */
    private Map<String, Object> requestSchema;
    
    /**
     * Response schema definition
     */
    private Map<String, Object> responseSchema;
    
    /**
     * Request parameters
     */
    private List<ParameterDefinition> parameters;
    
    /**
     * Request body definition
     */
    private RequestBodyDefinition requestBody;
    
    /**
     * Response definitions
     */
    private Map<String, ResponseDefinition> responses;
    
    /**
     * Security requirements
     */
    private List<SecurityRequirement> security;
    
    /**
     * Security schemes
     */
    private Map<String, SecurityScheme> securitySchemes;
    
    /**
     * Validation rules
     */
    private List<ValidationRule> validationRules;
    
    /**
     * Rate limiting configuration
     */
    private RateLimitConfig rateLimit;
    
    /**
     * Caching configuration
     */
    private CacheConfig cache;
    
    /**
     * Monitoring configuration
     */
    private MonitoringConfig monitoring;
    
    /**
     * Endpoint metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Endpoint version
     */
    private String version;
    
    /**
     * Endpoint group
     */
    private String group;
    
    /**
     * Endpoint namespace
     */
    private String namespace;
    
    /**
     * Endpoint owner
     */
    private String owner;
    
    /**
     * Endpoint maintainer
     */
    private String maintainer;
    
    /**
     * Endpoint documentation URL
     */
    private String documentationUrl;
    
    /**
     * Endpoint example URL
     */
    private String exampleUrl;
    
    /**
     * Endpoint test URL
     */
    private String testUrl;
    
    /**
     * Endpoint health check URL
     */
    private String healthCheckUrl;
    
    /**
     * Endpoint metrics URL
     */
    private String metricsUrl;
    
    /**
     * Endpoint logs URL
     */
    private String logsUrl;
    
    /**
     * Endpoint configuration
     */
    private Map<String, Object> configuration;
    
    /**
     * Endpoint environment variables
     */
    private Map<String, String> environment;
    
    /**
     * Endpoint dependencies
     */
    private List<String> dependencies;
    
    /**
     * Endpoint requirements
     */
    private List<String> requirements;
    
    /**
     * Endpoint constraints
     */
    private List<String> constraints;
    
    /**
     * Endpoint assumptions
     */
    private List<String> assumptions;
    
    /**
     * Endpoint risks
     */
    private List<String> risks;
    
    /**
     * Endpoint mitigations
     */
    private List<String> mitigations;
    
    /**
     * Endpoint testing strategy
     */
    private String testingStrategy;
    
    /**
     * Endpoint deployment strategy
     */
    private String deploymentStrategy;
    
    /**
     * Endpoint rollback strategy
     */
    private String rollbackStrategy;
    
    /**
     * Endpoint monitoring strategy
     */
    private String monitoringStrategy;
    
    /**
     * Endpoint alerting strategy
     */
    private String alertingStrategy;
    
    /**
     * Endpoint backup strategy
     */
    private String backupStrategy;
    
    /**
     * Endpoint recovery strategy
     */
    private String recoveryStrategy;
    
    /**
     * Endpoint scaling strategy
     */
    private String scalingStrategy;
    
    /**
     * Endpoint security strategy
     */
    private String securityStrategy;
    
    /**
     * Endpoint compliance strategy
     */
    private String complianceStrategy;
    
    /**
     * Endpoint governance strategy
     */
    private String governanceStrategy;
    
    /**
     * Endpoint lifecycle strategy
     */
    private String lifecycleStrategy;
    
    /**
     * Endpoint maintenance strategy
     */
    private String maintenanceStrategy;
    
    /**
     * Endpoint support strategy
     */
    private String supportStrategy;
    
    /**
     * Endpoint training strategy
     */
    private String trainingStrategy;
    
    /**
     * Endpoint documentation strategy
     */
    private String documentationStrategy;
    
    /**
     * Endpoint communication strategy
     */
    private String communicationStrategy;
    
    /**
     * Endpoint stakeholder strategy
     */
    private String stakeholderStrategy;
    
    /**
     * Endpoint success criteria
     */
    private List<String> successCriteria;
    
    /**
     * Endpoint acceptance criteria
     */
    private List<String> acceptanceCriteria;
    
    /**
     * Endpoint quality criteria
     */
    private List<String> qualityCriteria;
    
    /**
     * Endpoint performance criteria
     */
    private List<String> performanceCriteria;
    
    /**
     * Endpoint security criteria
     */
    private List<String> securityCriteria;
    
    /**
     * Endpoint compliance criteria
     */
    private List<String> complianceCriteria;
    
    /**
     * Endpoint governance criteria
     */
    private List<String> governanceCriteria;
    
    /**
     * Endpoint lifecycle criteria
     */
    private List<String> lifecycleCriteria;
    
    /**
     * Endpoint maintenance criteria
     */
    private List<String> maintenanceCriteria;
    
    /**
     * Endpoint support criteria
     */
    private List<String> supportCriteria;
    
    /**
     * Endpoint training criteria
     */
    private List<String> trainingCriteria;
    
    /**
     * Endpoint documentation criteria
     */
    private List<String> documentationCriteria;
    
    /**
     * Endpoint communication criteria
     */
    private List<String> communicationCriteria;
    
    /**
     * Endpoint stakeholder criteria
     */
    private List<String> stakeholderCriteria;
    
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
        private String schema;
        private Object example;
        private Map<String, Object> examples;
    }
    
    /**
     * Request Body Definition
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RequestBodyDefinition {
        private String description;
        private Boolean required;
        private Map<String, Object> content;
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
        private Map<String, Object> headers;
        private Map<String, Object> content;
        private Map<String, Object> links;
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
     * Security Scheme
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityScheme {
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
     * Validation Rule
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ValidationRule {
        private String field;
        private String rule;
        private String message;
        private Object value;
        private String operator;
        private Boolean required;
    }
    
    /**
     * Rate Limit Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitConfig {
        private Integer requestsPerMinute;
        private Integer requestsPerHour;
        private Integer requestsPerDay;
        private Boolean enabled;
        private String strategy;
    }
    
    /**
     * Cache Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheConfig {
        private Boolean enabled;
        private Long ttlSeconds;
        private String strategy;
        private Integer maxSize;
        private String evictionPolicy;
    }
    
    /**
     * Monitoring Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitoringConfig {
        private Boolean enabled;
        private List<String> metrics;
        private List<String> alerts;
        private String dashboard;
        private String logs;
    }
    
    // Additional methods for backend compatibility
    public String getEndpointPath() {
        return path;
    }
}