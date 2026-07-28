package ai.fabric.execution.config;

import ai.fabric.chat.config.ChatSessionAutoConfiguration;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.integration.chat.ChatSessionAIExecutionConversationRecorder;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = ChatSessionAutoConfiguration.class)
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
}
