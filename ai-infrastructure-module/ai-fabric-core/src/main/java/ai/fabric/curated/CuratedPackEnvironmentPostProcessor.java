package ai.fabric.curated;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * Loads curated pack defaults (modes + prompt bundles) into the Spring environment.
 *
 * <p>Activated via {@code ai.curated.pack}. Packs live under {@code classpath:ai-curated/packs/<pack>.yml}.</p>
 *
 * <p>Property source is added last (lowest precedence) so application config can override it deterministically.</p>
 */
public class CuratedPackEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_PACK = "ai.curated.pack";
    private static final String RESOURCE_PREFIX = "classpath:ai-curated/packs/";
    private static final String RESOURCE_SUFFIX = ".yml";

    // Must run after ConfigDataEnvironmentPostProcessor so application.yml can set ai.curated.pack.
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment == null) {
            return;
        }

        String rawPack = environment.getProperty(PROPERTY_PACK);
        if (!StringUtils.hasText(rawPack)) {
            return;
        }

        String pack = normalizePack(rawPack);
        if (!StringUtils.hasText(pack)) {
            return;
        }

        ResourceLoader loader = new DefaultResourceLoader(getClass().getClassLoader());
        String location = RESOURCE_PREFIX + pack + RESOURCE_SUFFIX;
        Resource resource = loader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Unknown curated pack '" + rawPack + "'. Expected resource: " + location);
        }

        YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources;
        try {
            sources = yamlLoader.load("ai-curated-pack:" + pack, resource);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load curated pack '" + rawPack + "' from " + location, ex);
        }

        if (sources == null || sources.isEmpty()) {
            return;
        }

        for (PropertySource<?> source : sources) {
            if (source == null) {
                continue;
            }
            environment.getPropertySources().addLast(source);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    private static String normalizePack(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        // Allow safe file-name-ish pack keys.
        trimmed = trimmed.replaceAll("[^a-z0-9_-]+", "-");
        trimmed = trimmed.replaceAll("-+", "-");
        trimmed = trimmed.replaceAll("^-+|-+$", "");
        return trimmed.isEmpty() ? null : trimmed;
    }
}
