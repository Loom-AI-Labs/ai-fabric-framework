package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.config.AIExecutionProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * One-shot bounded classpath or mounted-file specialist resource loader.
 */
public final class DefaultSpecialistManifestLoader
    implements SpecialistManifestLoader {

    private final ObjectMapper strictMapper;
    private final ObjectMapper yamlMapper;
    private final CanonicalJsonSupport canonicalJson;
    private final PathMatchingResourcePatternResolver resourceResolver;

    public DefaultSpecialistManifestLoader(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.strictMapper = objectMapper.copy()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.yamlMapper = new ObjectMapper(new YAMLFactory())
            .findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.canonicalJson = new CanonicalJsonSupport(strictMapper);
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    @Override
    public SpecialistResourceBundle load(
        AIExecutionProperties.Manifests properties
    ) {
        Objects.requireNonNull(properties, "properties is required");
        if (!properties.isEnabled()) {
            return SpecialistResourceBundle.empty();
        }
        List<LoadedSpecialistManifest> manifests = new ArrayList<>();
        List<SpecialistSchemaDefinition> schemas = new ArrayList<>();
        List<SpecialistPromptProfile> promptProfiles = new ArrayList<>();
        List<SpecialistCompilationDiagnostic> diagnostics = new ArrayList<>();
        for (String location : properties.getLocations()) {
            List<Resource> resources;
            try {
                resources = resources(location);
            } catch (SpecialistManifestException ex) {
                if (properties.isFailFast()) {
                    throw ex;
                }
                diagnostics.add(diagnostic(ex));
                continue;
            }
            for (Resource resource : resources) {
                try {
                    parse(
                        resource,
                        properties,
                        manifests,
                        schemas,
                        promptProfiles
                    );
                } catch (SpecialistManifestException ex) {
                    if (properties.isFailFast()) {
                        throw ex;
                    }
                    diagnostics.add(diagnostic(ex));
                }
            }
        }
        return new SpecialistResourceBundle(
            manifests,
            schemas,
            promptProfiles,
            diagnostics
        );
    }

    private List<Resource> resources(String location) {
        try {
            return Arrays.stream(resourceResolver.getResources(location))
                .sorted(Comparator.comparing(Resource::getDescription))
                .toList();
        } catch (IOException ex) {
            throw new SpecialistManifestException(
                "MANIFEST_LOCATION_UNREADABLE",
                "A configured specialist resource location could not be read.",
                safeSource(location),
                ex
            );
        }
    }

    private void parse(
        Resource resource,
        AIExecutionProperties.Manifests properties,
        List<LoadedSpecialistManifest> manifests,
        List<SpecialistSchemaDefinition> schemas,
        List<SpecialistPromptProfile> promptProfiles
    ) {
        String source = safeSource(resource.getFilename());
        byte[] bytes = readBounded(
            resource,
            properties.getMaxResourceBytes(),
            source
        );
        List<LoadedSpecialistManifest> parsedManifests = new ArrayList<>();
        List<SpecialistSchemaDefinition> parsedSchemas = new ArrayList<>();
        List<SpecialistPromptProfile> parsedPromptProfiles =
            new ArrayList<>();
        try {
            ObjectMapper parser = yaml(resource) ? yamlMapper : strictMapper;
            MappingIterator<JsonNode> documents = parser.readerFor(
                JsonNode.class
            ).readValues(bytes);
            int document = 0;
            while (documents.hasNextValue()) {
                JsonNode node = documents.nextValue();
                document++;
                if (node == null || node.isNull()) {
                    continue;
                }
                String documentSource = source + "#" + document;
                classify(
                    node,
                    documentSource,
                    properties.getMaxManifestBytes(),
                    parsedManifests,
                    parsedSchemas,
                    parsedPromptProfiles
                );
            }
            manifests.addAll(parsedManifests);
            schemas.addAll(parsedSchemas);
            promptProfiles.addAll(parsedPromptProfiles);
        } catch (SpecialistManifestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new SpecialistManifestException(
                "MANIFEST_PARSE_FAILED",
                "A specialist resource could not be parsed.",
                source,
                ex
            );
        }
    }

    private void classify(
        JsonNode node,
        String source,
        int maxManifestBytes,
        List<LoadedSpecialistManifest> manifests,
        List<SpecialistSchemaDefinition> schemas,
        List<SpecialistPromptProfile> promptProfiles
    ) throws JsonProcessingException {
        if (!node.isObject()) {
            throw new SpecialistManifestException(
                "RESOURCE_ROOT_INVALID",
                "A specialist resource must be a JSON object.",
                source
            );
        }
        JsonNode kindNode = node.get("kind");
        if (kindNode == null || !kindNode.isTextual()) {
            throw new SpecialistManifestException(
                "RESOURCE_KIND_REQUIRED",
                "A specialist resource kind is required.",
                source
            );
        }
        switch (kindNode.textValue()) {
            case "Specialist" -> {
                SpecialistManifest manifest = strictMapper.treeToValue(
                    node,
                    SpecialistManifest.class
                );
                JsonNode canonical = strictMapper.valueToTree(manifest);
                if (canonicalJson.write(canonical).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8
                    ).length > maxManifestBytes) {
                    throw new SpecialistManifestException(
                        "MANIFEST_TOO_LARGE",
                        "A specialist manifest exceeds its configured size limit.",
                        source
                    );
                }
                manifests.add(new LoadedSpecialistManifest(
                    manifest,
                    canonicalJson.hash(canonical),
                    source
                ));
            }
            case "SpecialistSchema" -> {
                SpecialistSchemaDefinition schema = strictMapper.treeToValue(
                    node,
                    SpecialistSchemaDefinition.class
                );
                schema.id();
                schemas.add(schema);
            }
            case "SpecialistPromptProfile" -> {
                SpecialistPromptProfile profile = strictMapper.treeToValue(
                    node,
                    SpecialistPromptProfile.class
                );
                profile.id();
                promptProfiles.add(profile);
            }
            default -> throw new SpecialistManifestException(
                "RESOURCE_KIND_UNSUPPORTED",
                "The specialist resource kind is not supported.",
                source
            );
        }
    }

    private byte[] readBounded(
        Resource resource,
        int maxBytes,
        String source
    ) {
        try (InputStream input = resource.getInputStream()) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new SpecialistManifestException(
                    "RESOURCE_TOO_LARGE",
                    "A specialist resource exceeds its configured size limit.",
                    source
                );
            }
            return bytes;
        } catch (SpecialistManifestException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new SpecialistManifestException(
                "RESOURCE_UNREADABLE",
                "A specialist resource could not be read.",
                source,
                ex
            );
        }
    }

    private boolean yaml(Resource resource) {
        String filename = resource.getFilename();
        if (filename == null) {
            return false;
        }
        String normalized = filename.toLowerCase(java.util.Locale.ROOT);
        return normalized.endsWith(".yml") || normalized.endsWith(".yaml");
    }

    private String safeSource(String value) {
        if (value == null || value.isBlank()) {
            return "configured-resource";
        }
        String normalized = value.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        String filename = separator >= 0
            ? normalized.substring(separator + 1)
            : normalized;
        return filename.length() > 200
            ? filename.substring(filename.length() - 200)
            : filename;
    }

    private SpecialistCompilationDiagnostic diagnostic(
        SpecialistManifestException failure
    ) {
        return new SpecialistCompilationDiagnostic(
            failure.reason(),
            failure.getMessage(),
            failure.source() != null
                ? failure.source()
                : "configured-resource"
        );
    }
}
