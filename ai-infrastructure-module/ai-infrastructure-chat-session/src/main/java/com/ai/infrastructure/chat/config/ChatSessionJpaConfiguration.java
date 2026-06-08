package com.ai.infrastructure.chat.config;

import com.ai.infrastructure.chat.repository.ChatSessionRepository;
import com.ai.infrastructure.chat.spi.ChatSessionStorageProvider;
import com.ai.infrastructure.chat.storage.DefaultDatabaseChatSessionStorage;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(EntityManagerFactory.class)
@EnableJpaRepositories(basePackageClasses = ChatSessionRepository.class)
class ChatSessionJpaConfiguration {

    @Bean
    @ConditionalOnMissingBean(ChatSessionStorageProvider.class)
    ChatSessionStorageProvider chatSessionStorageProvider(ChatSessionRepository repository) {
        return new DefaultDatabaseChatSessionStorage(repository);
    }
}
