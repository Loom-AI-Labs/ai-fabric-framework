package ai.fabric.chat.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class})
@AutoConfigurationPackage(basePackageClasses = {
    ai.fabric.chat.domain.ChatSession.class
})
@ConditionalOnProperty(prefix = "ai.chat", name = "enabled", havingValue = "true")
public class ChatSessionAutoConfigurationPackages {
    public static final String BASE_PACKAGE = "ai.fabric.chat";
}
