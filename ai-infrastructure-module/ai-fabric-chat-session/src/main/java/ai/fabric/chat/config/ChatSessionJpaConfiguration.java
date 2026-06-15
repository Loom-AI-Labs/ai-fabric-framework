package ai.fabric.chat.config;

import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.spi.ChatSessionStorageProvider;
import ai.fabric.chat.storage.DefaultDatabaseChatSessionStorage;
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
