package ai.fabric.chat.config;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationControlStore;
import ai.fabric.chat.control.DefaultConversationControlGateway;
import ai.fabric.chat.control.JdbcConversationControlStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnProperty(prefix = "ai.chat.control", name = "enabled", havingValue = "true")
class ConversationControlJdbcConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ConversationControlStore.class)
    ConversationControlStore conversationControlStore(
        DataSource dataSource,
        ObjectMapper objectMapper,
        ConversationControlProperties properties
    ) {
        return new JdbcConversationControlStore(
            dataSource,
            objectMapper,
            properties.isInitializeSchema()
        );
    }

    @Bean
    @ConditionalOnMissingBean(ConversationControlGateway.class)
    ConversationControlGateway conversationControlGateway(ConversationControlStore store) {
        return new DefaultConversationControlGateway(store);
    }
}
