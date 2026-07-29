package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.specialist.DefaultSpecialistRegistry;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistDefinitionValidator;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds one immutable registry before it is published to the application.
 */
public final class SpecialistRegistryBootstrap {

    private static final Logger log = LoggerFactory.getLogger(
        SpecialistRegistryBootstrap.class
    );

    private final SpecialistRegistry registry;
    private final SpecialistManifestRuntimeStatus status;
    private final SpecialistJsonSchemaRegistry schemaRegistry;
    private final SpecialistPromptProfileRegistry promptProfileRegistry;

    public SpecialistRegistryBootstrap(
        List<SpecialistDefinition<?, ?>> javaDefinitions,
        SpecialistResourceBundle resources,
        SpecialistManifestCompiler compiler,
        SpecialistDefinitionValidator definitionValidator,
        SpecialistGroundingValidatorRegistry groundingValidators,
        SpecialistFinalOutputValidatorRegistry finalOutputValidators,
        SpecialistDirectOutputProjectorRegistry directOutputProjectors,
        SpecialistOutputNormalizerRegistry outputNormalizers,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        ObjectMapper objectMapper,
        Set<String> iterativeModes,
        AIExecutionProperties.Manifests properties,
        SpecialistManifestMetrics metrics
    ) {
        this(
            javaDefinitions,
            resources,
            compiler,
            definitionValidator,
            groundingValidators,
            finalOutputValidators,
            directOutputProjectors,
            outputNormalizers,
            new SpecialistInputContinuationRegistry(List.of()),
            schemaValidator,
            canonicalJson,
            objectMapper,
            iterativeModes,
            properties,
            metrics
        );
    }

    public SpecialistRegistryBootstrap(
        List<SpecialistDefinition<?, ?>> javaDefinitions,
        SpecialistResourceBundle resources,
        SpecialistManifestCompiler compiler,
        SpecialistDefinitionValidator definitionValidator,
        SpecialistGroundingValidatorRegistry groundingValidators,
        SpecialistFinalOutputValidatorRegistry finalOutputValidators,
        SpecialistDirectOutputProjectorRegistry directOutputProjectors,
        SpecialistOutputNormalizerRegistry outputNormalizers,
        SpecialistInputContinuationRegistry inputContinuations,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        ObjectMapper objectMapper,
        Set<String> iterativeModes,
        AIExecutionProperties.Manifests properties,
        SpecialistManifestMetrics metrics
    ) {
        Objects.requireNonNull(resources, "resources is required");
        Objects.requireNonNull(compiler, "compiler is required");
        Objects.requireNonNull(
            definitionValidator,
            "definitionValidator is required"
        );
        Objects.requireNonNull(properties, "properties is required");
        SpecialistManifestMetrics runtimeMetrics = metrics != null
            ? metrics
            : SpecialistManifestMetrics.noop();
        List<SpecialistCompilationDiagnostic> diagnostics = new ArrayList<>(
            resources.diagnostics()
        );
        List<SpecialistSchemaDefinition> validSchemas = validSchemas(
            resources.schemas(),
            schemaValidator,
            properties,
            diagnostics,
            runtimeMetrics
        );
        List<SpecialistPromptProfile> validProfiles = validProfiles(
            resources.promptProfiles(),
            properties,
            diagnostics,
            runtimeMetrics
        );
        this.schemaRegistry = new SpecialistJsonSchemaRegistry(
            validSchemas,
            schemaValidator
        );
        this.promptProfileRegistry = new SpecialistPromptProfileRegistry(
            validProfiles
        );

        List<RegisteredSpecialist> registered = new ArrayList<>();
        for (SpecialistDefinition<?, ?> definition :
            javaDefinitions == null
                ? List.<SpecialistDefinition<?, ?>>of()
                : javaDefinitions) {
            definitionValidator.validate(definition);
            registered.add(RegisteredSpecialist.javaDefinition(definition));
        }
        Set<SpecialistId> ids = new LinkedHashSet<>();
        registered.forEach(item -> ids.add(item.id()));
        int manifestCount = 0;
        for (LoadedSpecialistManifest loaded : resources.manifests()) {
            SpecialistCompilationContext context =
                new SpecialistCompilationContext(
                    schemaRegistry,
                    promptProfileRegistry,
                    groundingValidators,
                    finalOutputValidators,
                    directOutputProjectors,
                    outputNormalizers,
                    inputContinuations,
                    schemaValidator,
                    definitionValidator,
                    canonicalJson,
                    objectMapper,
                    iterativeModes,
                    loaded.source(),
                    loaded.contentHash()
                );
            try {
                SpecialistCompilationResult result = compiler.compile(
                    loaded,
                    context
                );
                if (!ids.add(result.specialist().id())) {
                    throw new SpecialistManifestException(
                        "DUPLICATE_SPECIALIST_ID",
                        "Duplicate specialist definition "
                            + result.specialist().id() + ".",
                        loaded.source()
                    );
                }
                registered.add(result.specialist());
                diagnostics.addAll(result.diagnostics());
                manifestCount++;
                runtimeMetrics.recordLoad("loaded", "none");
                runtimeMetrics.recordValidation("valid", "none");
            } catch (SpecialistManifestException ex) {
                handle(
                    ex,
                    properties,
                    diagnostics,
                    runtimeMetrics
                );
            }
        }
        this.registry = new DefaultSpecialistRegistry(
            registered,
            definitionValidator
        );
        int javaCount = registered.size() - manifestCount;
        runtimeMetrics.recordRegistryCounts(javaCount, manifestCount);
        this.status = new SpecialistManifestRuntimeStatus(
            properties.isEnabled(),
            diagnostics.isEmpty(),
            registered.size(),
            manifestCount,
            javaCount,
            registry.registryContentHash(),
            diagnostics
        );
    }

    public SpecialistRegistry registry() {
        return registry;
    }

    public SpecialistManifestRuntimeStatus status() {
        return status;
    }

    public SpecialistJsonSchemaRegistry schemaRegistry() {
        return schemaRegistry;
    }

    public SpecialistPromptProfileRegistry promptProfileRegistry() {
        return promptProfileRegistry;
    }

    private List<SpecialistSchemaDefinition> validSchemas(
        List<SpecialistSchemaDefinition> schemas,
        SpecialistJsonSchemaValidator validator,
        AIExecutionProperties.Manifests properties,
        List<SpecialistCompilationDiagnostic> diagnostics,
        SpecialistManifestMetrics metrics
    ) {
        List<SpecialistSchemaDefinition> valid = new ArrayList<>();
        Set<SpecialistSchemaId> ids = new LinkedHashSet<>();
        for (SpecialistSchemaDefinition schema : schemas) {
            try {
                if (schema == null || schema.metadata() == null) {
                    throw new SpecialistManifestException(
                        "SCHEMA_RESOURCE_INCOMPLETE",
                        "Specialist schema metadata is required.",
                        "schema"
                    );
                }
                if (!ids.add(schema.id())) {
                    throw new SpecialistManifestException(
                        "DUPLICATE_SCHEMA_ID",
                        "Duplicate specialist schema " + schema.id() + ".",
                        "schema:" + schema.id()
                    );
                }
                new SpecialistJsonSchemaRegistry(List.of(schema), validator);
                valid.add(schema);
                metrics.recordValidation("valid", "none");
            } catch (SpecialistManifestException ex) {
                handle(ex, properties, diagnostics, metrics);
            }
        }
        return valid;
    }

    private List<SpecialistPromptProfile> validProfiles(
        List<SpecialistPromptProfile> profiles,
        AIExecutionProperties.Manifests properties,
        List<SpecialistCompilationDiagnostic> diagnostics,
        SpecialistManifestMetrics metrics
    ) {
        List<SpecialistPromptProfile> valid = new ArrayList<>();
        Set<SpecialistPromptProfileId> ids = new LinkedHashSet<>();
        for (SpecialistPromptProfile profile : profiles) {
            try {
                if (profile == null || profile.metadata() == null) {
                    throw new SpecialistManifestException(
                        "PROMPT_PROFILE_INCOMPLETE",
                        "Prompt profile metadata is required.",
                        "prompt-profile"
                    );
                }
                if (!ids.add(profile.id())) {
                    throw new SpecialistManifestException(
                        "DUPLICATE_PROMPT_PROFILE_ID",
                        "Duplicate specialist prompt profile "
                            + profile.id() + ".",
                        "prompt-profile:" + profile.id()
                    );
                }
                new SpecialistPromptProfileRegistry(List.of(profile));
                valid.add(profile);
                metrics.recordValidation("valid", "none");
            } catch (SpecialistManifestException ex) {
                handle(ex, properties, diagnostics, metrics);
            }
        }
        return valid;
    }

    private void handle(
        SpecialistManifestException failure,
        AIExecutionProperties.Manifests properties,
        List<SpecialistCompilationDiagnostic> diagnostics,
        SpecialistManifestMetrics metrics
    ) {
        metrics.recordLoad("rejected", failure.reason());
        metrics.recordValidation("invalid", failure.reason());
        if (properties.isFailFast()) {
            throw failure;
        }
        SpecialistCompilationDiagnostic diagnostic =
            new SpecialistCompilationDiagnostic(
                failure.reason(),
                failure.getMessage(),
                failure.source() != null
                    ? failure.source()
                    : "configured-resource"
            );
        diagnostics.add(diagnostic);
        log.warn(
            "Specialist manifest resource rejected reason={} source={}",
            diagnostic.reason(),
            diagnostic.source()
        );
    }
}
