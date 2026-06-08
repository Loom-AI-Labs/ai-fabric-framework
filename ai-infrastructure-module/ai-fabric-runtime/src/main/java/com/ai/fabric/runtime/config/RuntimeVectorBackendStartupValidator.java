package com.ai.fabric.runtime.config;

import com.ai.infrastructure.rag.VectorDatabaseService;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Fails startup early when an external vector backend is explicitly selected but no
 * {@link VectorDatabaseService} bean was registered.
 *
 * <p>This keeps canary rollouts honest: the candidate should fail with a direct vector-backend
 * configuration error instead of surfacing later as an unrelated controller autowiring failure.</p>
 */
@Component
public class RuntimeVectorBackendStartupValidator
    implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Set<String> NON_STRICT_TYPES = Set.of(
        "",
        "lucene",
        "memory",
        "none",
        "disabled",
        "false"
    );

    private static final Set<String> STRICT_EXTERNAL_TYPES = Set.of(
        "pinecone",
        "qdrant",
        "weaviate",
        "milvus"
    );

    private Environment environment = new StandardEnvironment();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment != null ? environment : new StandardEnvironment();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        String vectorType = normalize(environment.getProperty("ai.vector-db.type"));
        if (NON_STRICT_TYPES.contains(vectorType)) {
            return;
        }

        if (!STRICT_EXTERNAL_TYPES.contains(vectorType)) {
            throw new BeanCreationException(
                "Unsupported ai.vector-db.type='" + vectorType
                    + "'. Supported runtime values are lucene, memory, qdrant, pinecone, weaviate, milvus, none, or disabled."
            );
        }

        String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beanFactory,
            VectorDatabaseService.class,
            false,
            false
        );
        if (beanNames.length == 0) {
            throw new BeanCreationException(
                "Configured ai.vector-db.type='" + vectorType
                    + "' requires a VectorDatabaseService, but none was registered. Check runtime environment, provider packaging, and backend-specific properties."
            );
        }
    }

    private static String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "";
    }
}
