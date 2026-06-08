package com.ai.infrastructure.aspect;

import com.ai.infrastructure.annotation.AIProcess;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.dto.AICrudOperation;
import com.ai.infrastructure.dto.AIEntityConfig;
import com.ai.infrastructure.indexing.IndexingCoordinator;
import com.ai.infrastructure.service.AICapabilityService;
import java.lang.reflect.Method;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AICapableAspectTest {

    static class TestService {
        @AIProcess(entityType = "policy", processType = "create", generateEmbedding = false, indexForSearch = false)
        Object createAgreement() {
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
    }
}

