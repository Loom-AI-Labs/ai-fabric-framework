package ai.fabric.http;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "ai.http")
public class AIHttpClientProperties {

    /**
     * Connection establishment timeout.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Socket/read timeout for requests.
     */
    private Duration readTimeout = Duration.ofSeconds(60);
}

