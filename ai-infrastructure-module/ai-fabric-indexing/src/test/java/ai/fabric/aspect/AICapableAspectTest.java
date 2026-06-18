package ai.fabric.aspect;

import ai.fabric.annotation.AIProcess;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AICrudOperation;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingCoordinator;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.service.AICapabilityService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AICapableAspectTest {

    static class TestService {
        @AIProcess(entityType = "policy", processType = "create", generateEmbedding = false, indexForSearch = false)
        Object createAgreement() {
            return new Object();
        }

        @AIProcess(entityType = "policy", processType = "create")
        Object createIndexedAgreement() {
            return new Object();
        }
    }

    @Test
    void usesEntityTypeFromAIProcessAnnotation() throws Throwable {
        AIEntityConfigurationLoader configLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService aiCapabilityService = mock(AICapabilityService.class);
        IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

        AICapableAspect aspect = new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);

        Method method = TestService.class.getDeclaredMethod("createAgreement");
        AIProcess aiProcess = method.getAnnotation(AIProcess.class);

        AIEntityConfig config = AIEntityConfig.builder()
            .entityType("policy")
            .autoProcess(true)
            .features(java.util.List.of())
            .crudOperations(Map.of("create", AICrudOperation.builder()
                .operation("create")
                .generateEmbedding(false)
                .indexForSearch(false)
                .enableAnalysis(false)
                .removeFromSearch(true)
                .cleanupEmbeddings(true)
                .build()))
            .build();

        when(configLoader.getEntityConfig("policy")).thenReturn(config);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Object expected = new Object();

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getName()).thenReturn("createAgreement");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn(expected);

        Object actual = aspect.processAIMethod(joinPoint, aiProcess);

        assertSame(expected, actual);
        verify(configLoader, times(1)).getEntityConfig("policy");
        verify(joinPoint, times(1)).proceed();
    }

    @Test
    void propagatesOriginalMethodExceptionWithoutRetryingJoinPoint() throws Throwable {
        AIEntityConfigurationLoader configLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService aiCapabilityService = mock(AICapabilityService.class);
        IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

        AICapableAspect aspect = new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);

        Method method = TestService.class.getDeclaredMethod("createIndexedAgreement");
        AIProcess aiProcess = method.getAnnotation(AIProcess.class);
        when(configLoader.getEntityConfig("policy")).thenReturn(policyConfig(true, true));

        ProceedingJoinPoint joinPoint = joinPointFor(method);
        IllegalStateException originalFailure = new IllegalStateException("domain failure");
        when(joinPoint.proceed()).thenThrow(originalFailure);

        IllegalStateException thrown = assertThrows(
            IllegalStateException.class,
            () -> aspect.processAIMethod(joinPoint, aiProcess)
        );

        assertSame(originalFailure, thrown);
        verify(joinPoint, times(1)).proceed();
        verifyNoInteractions(indexingCoordinator);
    }

    @Test
    void configurationLookupFailureFallsBackToOriginalMethodOnce() throws Throwable {
        AIEntityConfigurationLoader configLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService aiCapabilityService = mock(AICapabilityService.class);
        IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

        AICapableAspect aspect = new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);

        Method method = TestService.class.getDeclaredMethod("createIndexedAgreement");
        AIProcess aiProcess = method.getAnnotation(AIProcess.class);
        when(configLoader.getEntityConfig("policy")).thenThrow(new IllegalStateException("config down"));

        ProceedingJoinPoint joinPoint = joinPointFor(method);
        Object expected = new Object();
        when(joinPoint.proceed()).thenReturn(expected);

        Object actual = aspect.processAIMethod(joinPoint, aiProcess);

        assertSame(expected, actual);
        verify(joinPoint, times(1)).proceed();
        verifyNoInteractions(indexingCoordinator);
    }

    @Test
    void indexingFailureDoesNotRetryOriginalMethod() throws Throwable {
        AIEntityConfigurationLoader configLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService aiCapabilityService = mock(AICapabilityService.class);
        IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

        AICapableAspect aspect = new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);

        Method method = TestService.class.getDeclaredMethod("createIndexedAgreement");
        AIProcess aiProcess = method.getAnnotation(AIProcess.class);
        when(configLoader.getEntityConfig("policy")).thenReturn(policyConfig(true, true));

        ProceedingJoinPoint joinPoint = joinPointFor(method);
        Object expected = new Object();
        when(joinPoint.proceed()).thenReturn(expected);
        doThrow(new IllegalStateException("queue down")).when(indexingCoordinator).handle(
            eq(expected),
            eq("policy"),
            eq(IndexingOperation.CREATE),
            any(IndexingActionPlan.class),
            eq(aiProcess)
        );

        Object actual = aspect.processAIMethod(joinPoint, aiProcess);

        assertSame(expected, actual);
        verify(joinPoint, times(1)).proceed();
        verify(indexingCoordinator, times(1)).handle(
            eq(expected),
            eq("policy"),
            eq(IndexingOperation.CREATE),
            any(IndexingActionPlan.class),
            eq(aiProcess)
        );
    }

    @Test
    void skipsIndexingWhenAnnotationDisablesSearchWork() throws Throwable {
        AIEntityConfigurationLoader configLoader = mock(AIEntityConfigurationLoader.class);
        AICapabilityService aiCapabilityService = mock(AICapabilityService.class);
        IndexingCoordinator indexingCoordinator = mock(IndexingCoordinator.class);

        AICapableAspect aspect = new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);

        Method method = TestService.class.getDeclaredMethod("createAgreement");
        AIProcess aiProcess = method.getAnnotation(AIProcess.class);
        when(configLoader.getEntityConfig("policy")).thenReturn(policyConfig(true, true));

        ProceedingJoinPoint joinPoint = joinPointFor(method);
        Object expected = new Object();
        when(joinPoint.proceed()).thenReturn(expected);

        Object actual = aspect.processAIMethod(joinPoint, aiProcess);

        assertSame(expected, actual);
        verify(joinPoint, times(1)).proceed();
        verify(indexingCoordinator, never()).handle(
            any(),
            any(),
            any(IndexingOperation.class),
            any(IndexingActionPlan.class),
            any(AIProcess.class)
        );
    }

    private ProceedingJoinPoint joinPointFor(Method method) {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getName()).thenReturn(method.getName());
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        return joinPoint;
    }

    private AIEntityConfig policyConfig(boolean generateEmbedding, boolean indexForSearch) {
        return AIEntityConfig.builder()
            .entityType("policy")
            .autoProcess(true)
            .features(List.of())
            .crudOperations(Map.of("create", AICrudOperation.builder()
                .operation("create")
                .generateEmbedding(generateEmbedding)
                .indexForSearch(indexForSearch)
                .enableAnalysis(false)
                .removeFromSearch(true)
                .cleanupEmbeddings(true)
                .build()))
            .build();
    }
}
