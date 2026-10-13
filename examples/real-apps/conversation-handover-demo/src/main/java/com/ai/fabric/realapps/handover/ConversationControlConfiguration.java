package com.ai.fabric.realapps.handover;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.DefaultConversationControlGateway;
import ai.fabric.chat.control.JdbcConversationControlStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ConversationControlConfiguration {

    @Bean("conversationControlObjectMapper")
    ObjectMapper conversationControlObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    ConversationControlGateway conversationControlGateway(
        DataSource dataSource,
        @Qualifier("conversationControlObjectMapper") ObjectMapper objectMapper
    ) {
        return new DefaultConversationControlGateway(
            new JdbcConversationControlStore(dataSource, objectMapper, true)
        );
    }
}
