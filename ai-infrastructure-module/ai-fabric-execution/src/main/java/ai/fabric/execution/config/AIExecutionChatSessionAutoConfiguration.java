package ai.fabric.execution.config;

import ai.fabric.chat.config.ChatSessionAutoConfiguration;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotProvider;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotRegistry;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.DefaultConversationManagerGateway;
import ai.fabric.execution.gateway.DefaultAIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.SharedInteractiveTurnCoordinator;
import ai.fabric.execution.integration.chat.ChatSessionAIExecutionConversationRecorder;
import ai.fabric.execution.integration.chat.ChatSessionAIExecutionConversationSnapshotProvider;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.privacy.pii.PIIDetectionService;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    after = {
        ChatSessionAutoConfiguration.class,
        AIExecutionAutoConfiguration.class
    }
)
@ConditionalOnClass(ChatSessionService.class)
@ConditionalOnBean(ChatSessionService.class)
public class AIExecutionChatSessionAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AIExecutionConversationRecorder.class)
    public AIExecutionConversationRecorder aiExecutionConversationRecorder(
        ChatSessionService chatSessionService,
        ObjectProvider<PIIDetectionService> piiDetectionService
    ) {
        return new ChatSessionAIExecutionConversationRecorder(
            chatSessionService,
            piiDetectionService
        );
    }

    @Bean
    @ConditionalOnBean({
        CanonicalJsonSupport.class,
        Clock.class
    })
    @ConditionalOnMissingBean(AIExecutionConversationSnapshotProvider.class)
    public AIExecutionConversationSnapshotProvider
        aiExecutionConversationSnapshotProvider(
            ChatSessionService chatSessionService,
            CanonicalJsonSupport canonicalJson,
            Clock clock
        ) {
        return new ChatSessionAIExecutionConversationSnapshotProvider(
            chatSessionService,
            canonicalJson,
            clock
        );
    }

    @Bean
    @ConditionalOnBean({
        SpecialistRegistry.class,
        AIExecutionConversationSnapshotProvider.class,
        AIExecutionConversationSnapshotRegistry.class,
        CanonicalJsonSupport.class
    })
    @ConditionalOnMissingBean(SharedInteractiveTurnCoordinator.class)
    public SharedInteractiveTurnCoordinator
        sharedInteractiveTurnCoordinator(
            SpecialistRegistry specialistRegistry,
            AIExecutionConversationSnapshotProvider snapshotProvider,
            AIExecutionConversationSnapshotRegistry snapshotRegistry,
            CanonicalJsonSupport canonicalJson
        ) {
        return new SharedInteractiveTurnCoordinator(
            specialistRegistry,
            snapshotProvider,
            snapshotRegistry,
            canonicalJson
        );
    }

    @Bean
    @ConditionalOnBean({
        AIExecutionGateway.class,
        SharedInteractiveTurnCoordinator.class,
        Clock.class
    })
    @ConditionalOnMissingBean(AIInteractiveExecutionGateway.class)
    public AIInteractiveExecutionGateway aiInteractiveExecutionGateway(
        AIExecutionGateway executionGateway,
        SharedInteractiveTurnCoordinator turnCoordinator,
        Clock clock
    ) {
        return new DefaultAIInteractiveExecutionGateway(
            executionGateway,
            turnCoordinator,
            clock
        );
    }

    @Bean
    @ConditionalOnBean({
        ConversationManagerRegistry.class,
        SpecialistClientFactory.class,
        SpecialistDelegationGateway.class,
        AIExecutionConversationRecorder.class,
        SharedInteractiveTurnCoordinator.class,
        CanonicalJsonSupport.class,
        Clock.class
    })
    @ConditionalOnProperty(
        prefix = "ai.execution.conversation-managers",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnMissingBean(ConversationManagerGateway.class)
    public ConversationManagerGateway conversationManagerGateway(
        ConversationManagerRegistry managerRegistry,
        SpecialistClientFactory specialistClientFactory,
        SpecialistDelegationGateway delegationGateway,
        AIExecutionConversationRecorder conversationRecorder,
        SharedInteractiveTurnCoordinator turnCoordinator,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        AIExecutionProperties properties
    ) {
        return new DefaultConversationManagerGateway(
            managerRegistry,
            specialistClientFactory,
            delegationGateway,
            conversationRecorder,
            turnCoordinator,
            canonicalJson,
            clock,
            properties.getConversationManagers().getMaxActive(),
            properties.getConversationManagers().getResultTtl()
        );
    }
}
