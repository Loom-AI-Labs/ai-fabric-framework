package com.ai.infrastructure.cache;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Cache Configuration
 * 
 * Configuration class for AI intelligent caching system with various
 * cache policies, eviction strategies, and performance settings.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheConfig {
    
    /**
     * Whether caching is enabled
     */
    @Builder.Default
    private Boolean enabled = true;
    
    /**
     * Cache type (memory, redis, hazelcast, etc.)
     */
    @Builder.Default
    private String type = "memory";
    
    /**
     * Default TTL for cache entries
     */
    @Builder.Default
    private Duration defaultTtl = Duration.ofHours(1);
    
    /**
     * Maximum cache size
     */
    @Builder.Default
    private Long maxSize = 10000L;
    
    /**
     * Cache eviction policy
     */
    @Builder.Default
    private String evictionPolicy = "LRU";
    
    /**
     * Whether to enable cache metrics
     */
    @Builder.Default
    private Boolean enableMetrics = true;
    
    /**
     * Whether to enable cache statistics
     */
    @Builder.Default
    private Boolean enableStatistics = true;
    
    /**
     * Whether to enable cache monitoring
     */
    @Builder.Default
    private Boolean enableMonitoring = true;
    
    /**
     * Whether to enable cache alerting
     */
    @Builder.Default
    private Boolean enableAlerting = false;
    
    /**
     * Whether to enable cache optimization
     */
    @Builder.Default
    private Boolean enableOptimization = true;
    
    /**
     * Whether to enable cache warming
     */
    @Builder.Default
    private Boolean enableWarming = false;
    
    /**
     * Whether to enable cache preloading
     */
    @Builder.Default
    private Boolean enablePreloading = false;
    
    /**
     * Whether to enable cache compression
     */
    @Builder.Default
    private Boolean enableCompression = false;
    
    /**
     * Whether to enable cache encryption
     */
    @Builder.Default
    private Boolean enableEncryption = false;
    
    /**
     * Whether to enable cache serialization
     */
    @Builder.Default
    private Boolean enableSerialization = true;
    
    /**
     * Whether to enable cache clustering
     */
    @Builder.Default
    private Boolean enableClustering = false;
    
    /**
     * Whether to enable cache persistence
     */
    @Builder.Default
    private Boolean enablePersistence = false;
    
    /**
     * Whether to enable cache replication
     */
    @Builder.Default
    private Boolean enableReplication = false;
    
    /**
     * Whether to enable cache partitioning
     */
    @Builder.Default
    private Boolean enablePartitioning = false;
    
    /**
     * Whether to enable cache sharding
     */
    @Builder.Default
    private Boolean enableSharding = false;
    
    /**
     * Whether to enable cache load balancing
     */
    @Builder.Default
    private Boolean enableLoadBalancing = false;
    
    /**
     * Whether to enable cache failover
     */
    @Builder.Default
    private Boolean enableFailover = false;
    
    /**
     * Whether to enable cache backup
     */
    @Builder.Default
    private Boolean enableBackup = false;
    
    /**
     * Whether to enable cache restore
     */
    @Builder.Default
    private Boolean enableRestore = false;
    
    /**
     * Whether to enable cache migration
     */
    @Builder.Default
    private Boolean enableMigration = false;
    
    /**
     * Whether to enable cache synchronization
     */
    @Builder.Default
    private Boolean enableSynchronization = false;
    
    /**
     * Whether to enable cache validation
     */
    @Builder.Default
    private Boolean enableValidation = true;
    
    /**
     * Whether to enable cache sanitization
     */
    @Builder.Default
    private Boolean enableSanitization = false;
    
    /**
     * Whether to enable cache auditing
     */
    @Builder.Default
    private Boolean enableAuditing = false;
    
    /**
     * Whether to enable cache compliance
     */
    @Builder.Default
    private Boolean enableCompliance = false;
    
    /**
     * Whether to enable cache governance
     */
    @Builder.Default
    private Boolean enableGovernance = false;
    
    /**
     * Whether to enable cache lifecycle management
     */
    @Builder.Default
    private Boolean enableLifecycleManagement = false;
    
    /**
     * Whether to enable cache maintenance
     */
    @Builder.Default
    private Boolean enableMaintenance = false;
    
    /**
     * Whether to enable cache support
     */
    @Builder.Default
    private Boolean enableSupport = false;
    
    /**
     * Whether to enable cache training
     */
    @Builder.Default
    private Boolean enableTraining = false;
    
    /**
     * Whether to enable cache documentation
     */
    @Builder.Default
    private Boolean enableDocumentation = false;
    
    /**
     * Whether to enable cache communication
     */
    @Builder.Default
    private Boolean enableCommunication = false;
    
    /**
     * Whether to enable cache stakeholder management
     */
    @Builder.Default
    private Boolean enableStakeholderManagement = false;
    
    /**
     * Cache name
     */
    private String name;
    
    /**
     * Cache description
     */
    private String description;
    
    /**
     * Cache version
     */
    private String version;
    
    /**
     * Cache owner
     */
    private String owner;
    
    /**
     * Cache maintainer
     */
    private String maintainer;
    
    /**
     * Cache namespace
     */
    private String namespace;
    
    /**
     * Cache group
     */
    private String group;
    
    /**
     * Cache priority
     */
    @Builder.Default
    private Integer priority = 5;
    
    /**
     * Cache weight
     */
    @Builder.Default
    private Double weight = 1.0;
    
    /**
     * Cache tags
     */
    private List<String> tags;
    
    /**
     * Cache metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * Cache configuration parameters
     */
    private Map<String, Object> parameters;
    
    /**
     * Cache environment variables
     */
    private Map<String, String> environment;
    
    /**
     * Cache dependencies
     */
    private List<String> dependencies;
    
    /**
     * Cache requirements
     */
    private List<String> requirements;
    
    /**
     * Cache constraints
     */
    private List<String> constraints;
    
    /**
     * Cache assumptions
     */
    private List<String> assumptions;
    
    /**
     * Cache risks
     */
    private List<String> risks;
    
    /**
     * Cache mitigations
     */
    private List<String> mitigations;
    
    /**
     * Cache testing strategy
     */
    private String testingStrategy;
    
    /**
     * Cache deployment strategy
     */
    private String deploymentStrategy;
    
    /**
     * Cache rollback strategy
     */
    private String rollbackStrategy;
    
    /**
     * Cache monitoring strategy
     */
    private String monitoringStrategy;
    
    /**
     * Cache alerting strategy
     */
    private String alertingStrategy;
    
    /**
     * Cache backup strategy
     */
    private String backupStrategy;
    
    /**
     * Cache recovery strategy
     */
    private String recoveryStrategy;
    
    /**
     * Cache scaling strategy
     */
    private String scalingStrategy;
    
    /**
     * Cache security strategy
     */
    private String securityStrategy;
    
    /**
     * Cache compliance strategy
     */
    private String complianceStrategy;
    
    /**
     * Cache governance strategy
     */
    private String governanceStrategy;
    
    /**
     * Cache lifecycle strategy
     */
    private String lifecycleStrategy;
    
    /**
     * Cache maintenance strategy
     */
    private String maintenanceStrategy;
    
    /**
     * Cache support strategy
     */
    private String supportStrategy;
    
    /**
     * Cache training strategy
     */
    private String trainingStrategy;
    
    /**
     * Cache documentation strategy
     */
    private String documentationStrategy;
    
    /**
     * Cache communication strategy
     */
    private String communicationStrategy;
    
    /**
     * Cache stakeholder strategy
     */
    private String stakeholderStrategy;
    
    /**
     * Cache success criteria
     */
    private List<String> successCriteria;
    
    /**
     * Cache acceptance criteria
     */
    private List<String> acceptanceCriteria;
    
    /**
     * Cache quality criteria
     */
    private List<String> qualityCriteria;
    
    /**
     * Cache performance criteria
     */
    private List<String> performanceCriteria;
    
    /**
     * Cache security criteria
     */
    private List<String> securityCriteria;
    
    /**
     * Cache compliance criteria
     */
    private List<String> complianceCriteria;
    
    /**
     * Cache governance criteria
     */
    private List<String> governanceCriteria;
    
    /**
     * Cache lifecycle criteria
     */
    private List<String> lifecycleCriteria;
    
    /**
     * Cache maintenance criteria
     */
    private List<String> maintenanceCriteria;
    
    /**
     * Cache support criteria
     */
    private List<String> supportCriteria;
    
    /**
     * Cache training criteria
     */
    private List<String> trainingCriteria;
    
    /**
     * Cache documentation criteria
     */
    private List<String> documentationCriteria;
    
    /**
     * Cache communication criteria
     */
    private List<String> communicationCriteria;
    
    /**
     * Cache stakeholder criteria
     */
    private List<String> stakeholderCriteria;
    
    /**
     * Cache performance settings
     */
    private PerformanceConfig performance;
    
    /**
     * Cache security settings
     */
    private SecurityConfig security;
    
    /**
     * Cache monitoring settings
     */
    private MonitoringConfig monitoring;
    
    /**
     * Cache alerting settings
     */
    private AlertingConfig alerting;
    
    /**
     * Cache backup settings
     */
    private BackupConfig backup;
    
    /**
     * Cache recovery settings
     */
    private RecoveryConfig recovery;
    
    /**
     * Cache scaling settings
     */
    private ScalingConfig scaling;
    
    /**
     * Cache compliance settings
     */
    private ComplianceConfig compliance;
    
    /**
     * Cache governance settings
     */
    private GovernanceConfig governance;
    
    /**
     * Cache lifecycle settings
     */
    private LifecycleConfig lifecycle;
    
    /**
     * Cache maintenance settings
     */
    private MaintenanceConfig maintenance;
    
    /**
     * Cache support settings
     */
    private SupportConfig support;
    
    /**
     * Cache training settings
     */
    private TrainingConfig training;
    
    /**
     * Cache documentation settings
     */
    private DocumentationConfig documentation;
    
    /**
     * Cache communication settings
     */
    private CommunicationConfig communication;
    
    /**
     * Cache stakeholder settings
     */
    private StakeholderConfig stakeholder;
    
    /**
     * Performance Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PerformanceConfig {
        @Builder.Default
        private Integer maxConcurrentRequests = 100;
        
        @Builder.Default
        private Integer threadPoolSize = 20;
        
        @Builder.Default
        private Boolean enableAsyncProcessing = true;
        
        @Builder.Default
        private Boolean enableBatching = true;
        
        @Builder.Default
        private Integer batchSize = 10;
        
        @Builder.Default
        private Duration batchTimeout = Duration.ofMillis(100);
        
        @Builder.Default
        private Boolean enableCompression = false;
        
        @Builder.Default
        private String compressionAlgorithm = "gzip";
        
        @Builder.Default
        private Boolean enableSerialization = true;
        
        @Builder.Default
        private String serializationFormat = "json";
    }
    
    /**
     * Security Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SecurityConfig {
        @Builder.Default
        private Boolean enableEncryption = false;
        
        @Builder.Default
        private String encryptionAlgorithm = "AES-256-GCM";
        
        @Builder.Default
        private Boolean enableAuthentication = false;
        
        @Builder.Default
        private String authenticationMethod = "token";
        
        @Builder.Default
        private Boolean enableAuthorization = false;
        
        @Builder.Default
        private String authorizationMethod = "rbac";
        
        @Builder.Default
        private Boolean enableAuditLogging = false;
        
        @Builder.Default
        private Boolean enableDataMasking = false;
        
        @Builder.Default
        private Boolean enableDataSanitization = false;
        
        @Builder.Default
        private Boolean enableDataValidation = true;
        
        @Builder.Default
        private Boolean enableDataPrivacy = false;
        
        @Builder.Default
        private Boolean enableDataCompliance = false;
        
        @Builder.Default
        private Boolean enableDataGovernance = false;
        
        @Builder.Default
        private Boolean enableDataLifecycle = false;
        
        @Builder.Default
        private Boolean enableDataRetention = false;
        
        @Builder.Default
        private Boolean enableDataBackup = false;
        
        @Builder.Default
        private Boolean enableDataRecovery = false;
        
        @Builder.Default
        private Boolean enableDataMigration = false;
        
        @Builder.Default
        private Boolean enableDataSynchronization = false;
        
        @Builder.Default
        private Boolean enableDataReplication = false;
        
        @Builder.Default
        private Boolean enableDataPartitioning = false;
        
        @Builder.Default
        private Boolean enableDataSharding = false;
        
        @Builder.Default
        private Boolean enableDataLoadBalancing = false;
        
        @Builder.Default
        private Boolean enableDataFailover = false;
        
        @Builder.Default
        private Boolean enableDataMonitoring = true;
        
        @Builder.Default
        private Boolean enableDataAlerting = false;
        
        @Builder.Default
        private Boolean enableDataReporting = true;
        
        @Builder.Default
        private Boolean enableDataVisualization = false;
        
        @Builder.Default
        private Boolean enableDataExploration = false;
        
        @Builder.Default
        private Boolean enableDataDiscovery = false;
        
        @Builder.Default
        private Boolean enableDataProfiling = false;
        
        @Builder.Default
        private Boolean enableDataLineage = false;
        
        
        @Builder.Default
        private Boolean enableDataMaintenance = false;
        
        @Builder.Default
        private Boolean enableDataSupport = false;
        
        @Builder.Default
        private Boolean enableDataTraining = false;
        
        @Builder.Default
        private Boolean enableDataDocumentation = false;
        
        @Builder.Default
        private Boolean enableDataCommunication = false;
        
        @Builder.Default
        private Boolean enableDataStakeholder = false;
    }
    
    /**
     * Monitoring Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonitoringConfig {
        @Builder.Default
        private Boolean enabled = true;
        
        @Builder.Default
        private String metricsPrefix = "ai.cache";
        
        @Builder.Default
        private Duration metricsInterval = Duration.ofSeconds(30);
        
        @Builder.Default
        private Boolean enableHealthChecks = true;
        
        @Builder.Default
        private Duration healthCheckInterval = Duration.ofMinutes(1);
        
        @Builder.Default
        private Boolean enableTracing = true;
        
        @Builder.Default
        private Boolean enableLogging = true;
        
        @Builder.Default
        private String logLevel = "INFO";
        
        @Builder.Default
        private Boolean enableProfiling = false;
        
        @Builder.Default
        private Boolean enableDebugging = false;
        
        @Builder.Default
        private Boolean enableTesting = false;
        
        @Builder.Default
        private Boolean enableValidation = true;
        
        @Builder.Default
        private Boolean enableSanitization = false;
        
        @Builder.Default
        private Boolean enableAuditing = false;
        
        @Builder.Default
        private Boolean enableCompliance = false;
        
        @Builder.Default
        private Boolean enableGovernance = false;
        
        @Builder.Default
        private Boolean enableLifecycle = false;
        
        @Builder.Default
        private Boolean enableMaintenance = false;
        
        @Builder.Default
        private Boolean enableSupport = false;
        
        @Builder.Default
        private Boolean enableTraining = false;
        
        @Builder.Default
        private Boolean enableDocumentation = false;
        
        @Builder.Default
        private Boolean enableCommunication = false;
        
        @Builder.Default
        private Boolean enableStakeholder = false;
    }
    
    /**
     * Alerting Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertingConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String alertChannel = "email";
        
        @Builder.Default
        private String alertLevel = "warning";
        
        @Builder.Default
        private Duration alertInterval = Duration.ofMinutes(5);
        
        @Builder.Default
        private Boolean enableEscalation = false;
        
        @Builder.Default
        private String escalationLevel = "critical";
        
        @Builder.Default
        private Duration escalationTimeout = Duration.ofMinutes(15);
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
        
        @Builder.Default
        private Boolean enableReporting = false;
        
        @Builder.Default
        private String reportingFrequency = "daily";
        
        @Builder.Default
        private Boolean enableDashboard = false;
        
        @Builder.Default
        private String dashboardUrl = "";
        
        @Builder.Default
        private Boolean enableMetrics = false;
        
        @Builder.Default
        private String metricsUrl = "";
        
        @Builder.Default
        private Boolean enableLogs = false;
        
        @Builder.Default
        private String logsUrl = "";
    }
    
    /**
     * Backup Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BackupConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String backupType = "full";
        
        @Builder.Default
        private String backupLocation = "local";
        
        @Builder.Default
        private Duration backupInterval = Duration.ofHours(24);
        
        @Builder.Default
        private Integer backupRetention = 7;
        
        @Builder.Default
        private Boolean enableCompression = true;
        
        @Builder.Default
        private String compressionAlgorithm = "gzip";
        
        @Builder.Default
        private Boolean enableEncryption = false;
        
        @Builder.Default
        private String encryptionAlgorithm = "AES-256-GCM";
        
        @Builder.Default
        private Boolean enableVerification = true;
        
        @Builder.Default
        private String verificationMethod = "checksum";
        
        @Builder.Default
        private Boolean enableScheduling = false;
        
        @Builder.Default
        private String scheduleExpression = "0 0 2 * * ?";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Recovery Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecoveryConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String recoveryType = "automatic";
        
        @Builder.Default
        private String recoveryLocation = "local";
        
        @Builder.Default
        private Duration recoveryTimeout = Duration.ofMinutes(30);
        
        @Builder.Default
        private Integer recoveryRetries = 3;
        
        @Builder.Default
        private Duration recoveryRetryInterval = Duration.ofMinutes(5);
        
        @Builder.Default
        private Boolean enableVerification = true;
        
        @Builder.Default
        private String verificationMethod = "checksum";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
        
        @Builder.Default
        private Boolean enableRollback = false;
        
        @Builder.Default
        private String rollbackMethod = "automatic";
        
        @Builder.Default
        private Boolean enableTesting = false;
        
        @Builder.Default
        private String testingMethod = "validation";
    }
    
    /**
     * Scaling Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ScalingConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String scalingType = "horizontal";
        
        @Builder.Default
        private Integer minInstances = 1;
        
        @Builder.Default
        private Integer maxInstances = 10;
        
        @Builder.Default
        private Integer targetInstances = 3;
        
        @Builder.Default
        private Double cpuThreshold = 70.0;
        
        @Builder.Default
        private Double memoryThreshold = 80.0;
        
        @Builder.Default
        private Double requestThreshold = 1000.0;
        
        @Builder.Default
        private Duration scaleUpCooldown = Duration.ofMinutes(5);
        
        @Builder.Default
        private Duration scaleDownCooldown = Duration.ofMinutes(10);
        
        @Builder.Default
        private Boolean enablePredictiveScaling = false;
        
        @Builder.Default
        private String predictiveModel = "linear";
        
        @Builder.Default
        private Boolean enableScheduledScaling = false;
        
        @Builder.Default
        private String scheduleExpression = "0 0 9 * * ?";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Compliance Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ComplianceConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private List<String> standards = List.of("GDPR", "CCPA");
        
        @Builder.Default
        private String complianceLevel = "basic";
        
        @Builder.Default
        private Boolean enableAuditing = false;
        
        @Builder.Default
        private String auditLevel = "basic";
        
        @Builder.Default
        private Boolean enableReporting = false;
        
        @Builder.Default
        private String reportingFrequency = "monthly";
        
        @Builder.Default
        private Boolean enableDocumentation = false;
        
        @Builder.Default
        private String documentationLevel = "basic";
        
        @Builder.Default
        private Boolean enableTraining = false;
        
        @Builder.Default
        private String trainingLevel = "basic";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Governance Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GovernanceConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String governanceLevel = "basic";
        
        @Builder.Default
        private Boolean enablePolicies = false;
        
        @Builder.Default
        private String policyLevel = "basic";
        
        @Builder.Default
        private Boolean enableProcedures = false;
        
        @Builder.Default
        private String procedureLevel = "basic";
        
        @Builder.Default
        private Boolean enableStandards = false;
        
        @Builder.Default
        private String standardLevel = "basic";
        
        @Builder.Default
        private Boolean enableGuidelines = false;
        
        @Builder.Default
        private String guidelineLevel = "basic";
        
        @Builder.Default
        private Boolean enableBestPractices = false;
        
        @Builder.Default
        private String bestPracticeLevel = "basic";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Lifecycle Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LifecycleConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String lifecycleStage = "development";
        
        @Builder.Default
        private Boolean enableStaging = false;
        
        @Builder.Default
        private String stagingLevel = "basic";
        
        @Builder.Default
        private Boolean enableProduction = false;
        
        @Builder.Default
        private String productionLevel = "basic";
        
        @Builder.Default
        private Boolean enableRetirement = false;
        
        @Builder.Default
        private String retirementLevel = "basic";
        
        @Builder.Default
        private Boolean enableMigration = false;
        
        @Builder.Default
        private String migrationLevel = "basic";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Maintenance Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MaintenanceConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String maintenanceType = "scheduled";
        
        @Builder.Default
        private Duration maintenanceInterval = Duration.ofDays(7);
        
        @Builder.Default
        private String maintenanceWindow = "02:00-04:00";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
        
        @Builder.Default
        private Boolean enableTesting = false;
        
        @Builder.Default
        private String testingLevel = "basic";
        
        @Builder.Default
        private Boolean enableValidation = false;
        
        @Builder.Default
        private String validationLevel = "basic";
        
        @Builder.Default
        private Boolean enableOptimization = false;
        
        @Builder.Default
        private String optimizationLevel = "basic";
    }
    
    /**
     * Support Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupportConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String supportLevel = "basic";
        
        @Builder.Default
        private String supportChannel = "email";
        
        @Builder.Default
        private String supportHours = "9-5";
        
        @Builder.Default
        private String supportTimezone = "UTC";
        
        @Builder.Default
        private Boolean enableEscalation = false;
        
        @Builder.Default
        private String escalationLevel = "critical";
        
        @Builder.Default
        private Duration escalationTimeout = Duration.ofHours(4);
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Training Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrainingConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String trainingLevel = "basic";
        
        @Builder.Default
        private String trainingType = "online";
        
        @Builder.Default
        private String trainingFormat = "video";
        
        @Builder.Default
        private Duration trainingDuration = Duration.ofHours(2);
        
        @Builder.Default
        private Boolean enableCertification = false;
        
        @Builder.Default
        private String certificationLevel = "basic";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Documentation Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DocumentationConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String documentationLevel = "basic";
        
        @Builder.Default
        private String documentationFormat = "markdown";
        
        @Builder.Default
        private String documentationStyle = "technical";
        
        @Builder.Default
        private Boolean enableVersioning = false;
        
        @Builder.Default
        private String versioningStrategy = "semantic";
        
        @Builder.Default
        private Boolean enableTranslation = false;
        
        @Builder.Default
        private String translationLevel = "basic";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
    }
    
    /**
     * Communication Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommunicationConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String communicationLevel = "basic";
        
        @Builder.Default
        private String communicationChannel = "email";
        
        @Builder.Default
        private String communicationFrequency = "weekly";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
        
        @Builder.Default
        private Boolean enableEscalation = false;
        
        @Builder.Default
        private String escalationLevel = "critical";
        
        @Builder.Default
        private Duration escalationTimeout = Duration.ofHours(4);
    }
    
    /**
     * Stakeholder Configuration
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StakeholderConfig {
        @Builder.Default
        private Boolean enabled = false;
        
        @Builder.Default
        private String stakeholderLevel = "basic";
        
        @Builder.Default
        private String stakeholderType = "internal";
        
        @Builder.Default
        private String stakeholderRole = "user";
        
        @Builder.Default
        private Boolean enableNotification = false;
        
        @Builder.Default
        private String notificationMethod = "email";
        
        @Builder.Default
        private Boolean enableEscalation = false;
        
        @Builder.Default
        private String escalationLevel = "critical";
        
        @Builder.Default
        private Duration escalationTimeout = Duration.ofHours(4);
    }
}