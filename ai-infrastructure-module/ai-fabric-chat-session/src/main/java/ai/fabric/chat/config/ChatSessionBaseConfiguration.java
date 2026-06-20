package ai.fabric.chat.config;

import ai.fabric.chat.pipeline.ConversationEnrichmentStep;
import ai.fabric.chat.pipeline.ConfirmationResolutionStep;
import ai.fabric.chat.pipeline.ConversationRecordingStep;
import ai.fabric.chat.pipeline.WorkingSetTargetSeedingStep;
import ai.fabric.chat.resolver.AnnotatedConfirmationInterceptorsResolver;
import ai.fabric.chat.resolver.CompoundConfirmationResolver;
import ai.fabric.chat.resolver.ConfiguredConfirmationInterceptorsResolver;
import ai.fabric.chat.resolver.ExpiredConfirmationResolver;
import ai.fabric.chat.resolver.SingleConfirmationPositiveResolver;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.service.ChatSessionServiceImpl;
import ai.fabric.chat.spi.ChatSessionAccessControlPolicy;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.spi.IntentResolver;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import ai.fabric.chat.storage.ChatSessionActionDraftStore;
import ai.fabric.chat.storage.ChatSessionPendingActionStore;
import ai.fabric.chat.strategy.MemoryStrategy;
import ai.fabric.chat.strategy.SlidingWindowMemoryStrategy;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Configuration(proxyBeanMethods = false)
class ChatSessionBaseConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MemoryStrategy chatMemoryStrategy() {
        return new SlidingWindowMemoryStrategy();
    }

    @Bean
    @ConditionalOnMissingBean
    ChatSessionService chatSessionService(ChatSessionStorageProvider storageProvider,
                                          ChatSessionAccessControlPolicy accessControlPolicy,
                                          MemoryStrategy memoryStrategy,
                                          ChatSessionProperties properties) {
        return new ChatSessionServiceImpl(storageProvider, accessControlPolicy, memoryStrategy, properties);
    }

    @Bean
    @Primary
    PendingActionStore pendingActionStore(ChatSessionStorageProvider storageProvider,
                                          ChatSessionProperties properties) {
        return new ChatSessionPendingActionStore(storageProvider, properties);
    }

    @Bean
    @ConditionalOnBean(ChatSessionService.class)
    ConversationEnrichmentStep conversationEnrichmentStep(ChatSessionService chatSessionService,
                                                          ChatSessionProperties properties,
                                                          PendingActionStore pendingActionStore,
                                                          ActionDraftStore actionDraftStore) {
        return new ConversationEnrichmentStep(chatSessionService, properties, pendingActionStore, actionDraftStore);
    }

    @Bean
    @Primary
    ActionDraftStore actionDraftStore(ChatSessionStorageProvider storageProvider) {
        return new ChatSessionActionDraftStore(storageProvider);
    }

    @Bean
    ExpiredConfirmationResolver expiredConfirmationResolver(PendingActionStore pendingActionStore) {
        return new ExpiredConfirmationResolver(pendingActionStore);
    }

    @Bean
    AnnotatedConfirmationInterceptorsResolver annotatedConfirmationInterceptorsResolver(PendingActionStore pendingActionStore,
                                                                                       ApplicationContext applicationContext) {
        return new AnnotatedConfirmationInterceptorsResolver(pendingActionStore, applicationContext);
    }

    @Bean
    ConfiguredConfirmationInterceptorsResolver configuredConfirmationInterceptorsResolver(
        PendingActionStore pendingActionStore,
        ObjectProvider<ConfirmationInterceptorCatalogProvider> catalogProvider
    ) {
        return new ConfiguredConfirmationInterceptorsResolver(pendingActionStore, catalogProvider);
    }

    @Bean
    CompoundConfirmationResolver compoundConfirmationResolver(PendingActionStore pendingActionStore) {
        return new CompoundConfirmationResolver(pendingActionStore);
    }

    @Bean
    SingleConfirmationPositiveResolver singleConfirmationPositiveResolver(PendingActionStore pendingActionStore) {
        return new SingleConfirmationPositiveResolver(pendingActionStore);
    }

    @Bean
    @ConditionalOnBean(ChatSessionService.class)
    ConfirmationResolutionStep confirmationResolutionStep(ChatSessionService chatSessionService,
                                                          ChatSessionProperties properties,
                                                          List<IntentResolver> resolvers) {
        return new ConfirmationResolutionStep(chatSessionService, properties, resolvers);
    }

    @Bean
    @ConditionalOnBean(ChatSessionService.class)
    ConversationRecordingStep conversationRecordingStep(ChatSessionService chatSessionService,
                                                        ChatSessionProperties properties,
                                                        ObjectProvider<PIIDetectionService> piiDetectionService) {
        return new ConversationRecordingStep(chatSessionService, properties, piiDetectionService);
    }

    @Bean
    @ConditionalOnBean(ChatSessionService.class)
    WorkingSetTargetSeedingStep workingSetTargetSeedingStep(ChatSessionService chatSessionService,
                                                            ChatSessionProperties properties) {
        return new WorkingSetTargetSeedingStep(chatSessionService, properties);
    }

    @Bean
    ChatSessionRequiredBeansValidator chatSessionRequiredBeansValidator(ChatSessionAccessControlPolicy ignoredPolicy,
                                                                        ObjectProvider<ChatSessionStorageProvider> storageProvider) {
        return new ChatSessionRequiredBeansValidator(storageProvider);
    }

    static final class ChatSessionRequiredBeansValidator implements SmartLifecycle {
        private final ObjectProvider<ChatSessionStorageProvider> storageProvider;
        private final AtomicBoolean running = new AtomicBoolean(false);

        ChatSessionRequiredBeansValidator(ObjectProvider<ChatSessionStorageProvider> storageProvider) {
            this.storageProvider = storageProvider;
        }

        @Override
        public void start() {
            ChatSessionStorageProvider provider = storageProvider != null ? storageProvider.getIfAvailable() : null;
            if (provider == null) {
                throw new IllegalStateException(
                    "ai.chat.enabled=true requires a ChatSessionStorageProvider (provide a bean or enable JPA so the default storage can be created)"
                );
            }
            running.set(true);
        }

        @Override
        public void stop() {
            running.set(false);
        }

        @Override
        public boolean isRunning() {
            return running.get();
        }

        @Override
        public int getPhase() {
            return Integer.MIN_VALUE;
        }
    }
}
