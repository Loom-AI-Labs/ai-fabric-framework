package com.ai.fabric.realapps.deploymentguard.specialist;

import ai.fabric.execution.specialist.manifest.SpecialistFinalOutputValidator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class DeploymentKnowledgeSpecialistConfiguration {

    public static final String CITATION_VALIDATOR =
        "deployment-evidence-citations@1";

    @Bean
    SpecialistFinalOutputValidator deploymentEvidenceCitationValidator() {
        return SpecialistFinalOutputValidator.named(
            CITATION_VALIDATOR,
            context -> {
                Set<String> available = context.evidence().stream()
                    .map(reference -> reference.evidenceId())
                    .collect(java.util.stream.Collectors.toCollection(
                        LinkedHashSet::new
                    ));
                JsonNode citations = context.output().path("evidenceIds");
                if (!citations.isArray() || citations.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Deployment answer must cite retrieved evidence"
                    );
                }
                for (JsonNode citation : citations) {
                    if (!citation.isTextual()
                        || !available.contains(citation.asText())) {
                        throw new IllegalArgumentException(
                            "Deployment answer cited evidence outside the retrieved boundary"
                        );
                    }
                }
            }
        );
    }
}
