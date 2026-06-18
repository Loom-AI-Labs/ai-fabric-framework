package ai.fabric.chat.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;

@AutoConfiguration
@AutoConfigureBefore({HibernateJpaAutoConfiguration.class, JpaRepositoriesAutoConfiguration.class})
@AutoConfigurationPackage(basePackageClasses = {
    ai.fabric.chat.domain.ChatSession.class
})
@ConditionalOnProperty(prefix = "ai.chat", name = "enabled", havingValue = "true")
public class ChatSessionAutoConfigurationPackages {
    public static final String BASE_PACKAGE = "ai.fabric.chat";
}
