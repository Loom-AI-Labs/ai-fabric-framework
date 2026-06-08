package com.ai.infrastructure.chat.config;

import com.ai.infrastructure.chat.spi.ChatSessionAccessControlPolicy;
import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@AutoConfigureAfter({AIInfrastructureAutoConfiguration.class, HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@EnableConfigurationProperties(ChatSessionProperties.class)
@ConditionalOnProperty(prefix = "ai.chat", name = "enabled", havingValue = "true")
@Import({ChatSessionBaseConfiguration.class, ChatSessionJpaConfiguration.class})
public class ChatSessionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionAutoConfiguration.class);

    public ChatSessionAutoConfiguration(ChatSessionProperties properties) {
        log.info(
            "Chat session module enabled (windowSize={}, maxContextChars={}, autoCreateSessions={}, memoryStrategy={})",
            properties.getWindowSize(),
            properties.getMaxContextChars(),
            properties.isAutoCreateSessions(),
            properties.getMemoryStrategy()
        );
    }
}
