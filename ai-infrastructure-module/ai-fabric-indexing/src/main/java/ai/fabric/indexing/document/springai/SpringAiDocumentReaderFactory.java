package ai.fabric.indexing.document.springai;

import org.springframework.ai.document.DocumentReader;
import org.springframework.ai.reader.JsonReader;
import org.springframework.ai.reader.TextReader;
import org.springframework.core.io.Resource;

import java.util.Objects;

/**
 * Creates Spring AI readers only after AI Fabric resource trust checks pass.
 */
public class SpringAiDocumentReaderFactory {

    public DocumentReader textReader(Resource resource, SpringAiTrustedResourcePolicy policy) {
        validate(resource, policy);
        return new TextReader(resource);
    }

    public DocumentReader jsonReader(Resource resource,
                                     SpringAiTrustedResourcePolicy policy,
                                     String... contentKeys) {
        validate(resource, policy);
        return contentKeys == null || contentKeys.length == 0
            ? new JsonReader(resource)
            : new JsonReader(resource, contentKeys);
    }

    private void validate(Resource resource, SpringAiTrustedResourcePolicy policy) {
        Objects.requireNonNull(policy, "policy is required");
        policy.validate(resource);
    }
}
