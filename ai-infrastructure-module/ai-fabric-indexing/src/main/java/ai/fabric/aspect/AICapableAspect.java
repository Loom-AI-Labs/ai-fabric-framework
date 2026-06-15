package ai.fabric.aspect;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIProcess;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingCoordinator;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.service.AICapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.util.StringUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * AICapable Aspect
 * 
 * Spring AOP aspect that intercepts methods annotated with @AICapable
 * and triggers automatic AI processing based on configuration.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@Aspect
@RequiredArgsConstructor
public class AICapableAspect {
    
    private final AIEntityConfigurationLoader configLoader;
    private final AICapabilityService aiCapabilityService;
    private final IndexingCoordinator indexingCoordinator;
    
    @Around("@annotation(aiCapable)")
    public Object processAICapableMethod(ProceedingJoinPoint joinPoint, AICapable aiCapable) throws Throwable {
        try {
            log.debug("Processing AI-capable method: {}", joinPoint.getSignature().getName());
            
            // Get method signature
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            
            // Get entity type from annotation or method name
            String entityType = getEntityType(aiCapable, method);
            
            // Load configuration for entity type
            AIEntityConfig config = configLoader.getEntityConfig(entityType);
            if (config == null) {
                log.warn("No configuration found for entity type: {}", entityType);
                return joinPoint.proceed();
            }
            
            // Check if auto-processing is enabled
            if (!config.isAutoProcess()) {
                log.debug("Auto-processing disabled for entity type: {}", entityType);
                return joinPoint.proceed();
            }
            
            // Process before method execution
            processBeforeMethod(joinPoint, config, entityType);
            
            // Execute the original method
            Object result = joinPoint.proceed();
            
            // Process after method execution
            processAfterMethod(joinPoint, result, config, entityType, null);
            
            return result;
            
        } catch (Exception e) {
            log.error("Error processing AI-capable method: {}", joinPoint.getSignature().getName(), e);
            // Don't fail the original method if AI processing fails
            return joinPoint.proceed();
        }
    }
    
    @Around("@annotation(aiProcess)")
    public Object processAIMethod(ProceedingJoinPoint joinPoint, AIProcess aiProcess) throws Throwable {
        try {
            log.debug("Processing AI method: {}", joinPoint.getSignature().getName());
            
            // Get method signature
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            
            // Framework contract: @AIProcess must declare entityType explicitly.
            String entityType = StringUtils.hasText(aiProcess.entityType()) ? aiProcess.entityType().trim() : null;
            if (!StringUtils.hasText(entityType)) {
                log.warn("Missing required @AIProcess(entityType=...) for method {}.{}; skipping AI processing for this invocation",
                    method.getDeclaringClass().getSimpleName(), method.getName());
                return joinPoint.proceed();
            }
            
            // Load configuration for entity type
            AIEntityConfig config = configLoader.getEntityConfig(entityType);
            if (config == null) {
                log.warn("No configuration found for entity type: {}", entityType);
                return joinPoint.proceed();
            }
            
            // Process before method execution
            processBeforeMethod(joinPoint, config, entityType);
            
            // Execute the original method
            Object result = joinPoint.proceed();
            
            // Process after method execution
            processAfterMethod(joinPoint, result, config, entityType, aiProcess);
            
            return result;
            
        } catch (Exception e) {
            log.error("Error processing AI method: {}", joinPoint.getSignature().getName(), e);
            // Don't fail the original method if AI processing fails
            return joinPoint.proceed();
        }
    }
    
    private String getEntityType(AICapable aiCapable, Method method) {
        if (!aiCapable.entityType().isEmpty()) {
            return aiCapable.entityType();
        }
        return null;
    }
    
    private void processBeforeMethod(ProceedingJoinPoint joinPoint, AIEntityConfig config, String entityType) {
        try {
            log.debug("Processing before method for entity type: {}", entityType);
            
            // Extract entity data from method arguments
            Object[] args = joinPoint.getArgs();
            if (args.length > 0) {
                Object entity = args[0];
                
                // Validate entity if needed
                if (config.getFeatures().contains("validation")) {
                    aiCapabilityService.validateEntity(entity, config);
                }
            }
            
        } catch (Exception e) {
            log.error("Error processing before method for entity type: {}", entityType, e);
        }
    }
    
    private void processAfterMethod(ProceedingJoinPoint joinPoint, Object result, AIEntityConfig config,
                                    String entityType, AIProcess aiProcess) {
        try {
            log.debug("Processing after method for entity type: {}", entityType);
            
            if (result != null) {
                // Determine operation type
                String operation = getOperationType(joinPoint);

                // Get CRUD operation configuration (allow both lower and upper case keys)
                var crudOperations = config.getCrudOperations();
                var crudOp = crudOperations != null ?
                    (crudOperations.containsKey(operation)
                        ? crudOperations.get(operation)
                        : crudOperations.get(operation.toUpperCase(java.util.Locale.ROOT)))
                    : null;
                if (crudOp == null) {
                    log.warn("No CRUD operation configuration found for: {}. Available keys: {}", operation,
                        crudOperations != null ? crudOperations.keySet() : "none");
                    return;
                }
                
                // Process entity based on configuration
                boolean shouldGenerateEmbedding = crudOp.isGenerateEmbedding();
                boolean shouldIndexForSearch = crudOp.isIndexForSearch();
                boolean shouldEnableAnalysis = crudOp.isEnableAnalysis();
                boolean shouldRemoveFromSearch = "delete".equals(operation) && crudOp.isRemoveFromSearch();
                boolean shouldCleanupEmbeddings = "delete".equals(operation) && crudOp.isCleanupEmbeddings();

                if (aiProcess != null) {
                    shouldGenerateEmbedding = shouldGenerateEmbedding && aiProcess.generateEmbedding();
                    shouldIndexForSearch = shouldIndexForSearch && aiProcess.indexForSearch();
                    shouldEnableAnalysis = shouldEnableAnalysis || aiProcess.enableAnalysis();
                }

                if (!shouldIndexForSearch) {
                    // If we are not indexing this invocation, skip embedding generation as well
                    shouldGenerateEmbedding = false;
                }

                  if ((shouldGenerateEmbedding || shouldIndexForSearch)
                      && TransactionSynchronizationManager.isSynchronizationActive()) {
                    final Object entityRef = result;
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (status == STATUS_ROLLED_BACK) {
                                try {
                                    aiCapabilityService.removeFromSearch(entityRef, config);
                                } catch (Exception ex) {
                                    log.warn("Failed to rollback searchable entity for {}:{}", entityType, getOperationType(joinPoint), ex);
                                }
                                try {
                                    aiCapabilityService.cleanupEmbeddings(entityRef, config);
                                } catch (Exception ex) {
                                    log.warn("Failed to rollback embeddings for {}:{}", entityType, getOperationType(joinPoint), ex);
                                }
                            }
                        }
                    });
                }

                log.debug("AI processing flags resolved for operation {}: generateEmbedding={}, indexForSearch={}, enableAnalysis={} (annotationPresent={})",
                    operation, shouldGenerateEmbedding, shouldIndexForSearch, shouldEnableAnalysis, aiProcess != null);

                  IndexingActionPlan actionPlan = new IndexingActionPlan(
                      shouldGenerateEmbedding,
                      shouldIndexForSearch,
                      shouldEnableAnalysis,
                      shouldRemoveFromSearch,
                      shouldCleanupEmbeddings
                  );

                  routeIndexingWork(result, entityType, operation, actionPlan, aiProcess);
            }
            
        } catch (Exception e) {
            log.error("Error processing after method for entity type: {}", entityType, e);
        }
    }
    
    private String getOperationType(ProceedingJoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().getName().toLowerCase();
        
        if (methodName.startsWith("create") || methodName.startsWith("save") || methodName.startsWith("add")) {
            return "create";
        } else if (methodName.startsWith("update") || methodName.startsWith("modify") || methodName.startsWith("edit")) {
            return "update";
        } else if (methodName.startsWith("delete") || methodName.startsWith("remove")) {
            return "delete";
        } else if (methodName.startsWith("search") || methodName.startsWith("find")) {
            return "search";
        } else if (methodName.startsWith("analyze")) {
            return "analyze";
        }
        
        return "create"; // Default
    }

    private void routeIndexingWork(
        Object result,
        String entityType,
        String operation,
        IndexingActionPlan actionPlan,
        AIProcess aiProcess
    ) {
        if (result == null || !actionPlan.requiresWork()) {
            return;
        }

        IndexingOperation indexingOperation = toIndexingOperation(operation);

        if (result instanceof Collection<?> collection) {
            collection.stream()
                .filter(Objects::nonNull)
                .forEach(entity -> indexingCoordinator.handle(entity, entityType, indexingOperation, actionPlan, aiProcess));
        } else if (result instanceof Optional<?> optional) {
            optional.ifPresent(entity -> indexingCoordinator.handle(entity, entityType, indexingOperation, actionPlan, aiProcess));
        } else {
            indexingCoordinator.handle(result, entityType, indexingOperation, actionPlan, aiProcess);
        }
    }

    private IndexingOperation toIndexingOperation(String operation) {
        return switch (operation.toLowerCase()) {
            case "create" -> IndexingOperation.CREATE;
            case "update" -> IndexingOperation.UPDATE;
            case "delete" -> IndexingOperation.DELETE;
            default -> IndexingOperation.CREATE;
        };
    }
}
