package ai.fabric.indexing.document.springai;

import ai.fabric.indexing.document.model.DocumentIngestionException;
import ai.fabric.indexing.document.model.DocumentIngestionFailureCode;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Defines which resources may be handed to Spring AI readers by AI Fabric ingestion code.
 */
public record SpringAiTrustedResourcePolicy(
    List<Path> trustedRoots,
    boolean allowClasspathResources,
    boolean allowInMemoryResources
) {

    public SpringAiTrustedResourcePolicy {
        trustedRoots = trustedRoots == null ? List.of() : trustedRoots.stream()
            .filter(Objects::nonNull)
            .map(path -> path.toAbsolutePath().normalize())
            .toList();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SpringAiTrustedResourcePolicy trustedRoot(Path root) {
        return builder().trustedRoot(root).build();
    }

    public void validate(Resource resource) {
        Objects.requireNonNull(resource, "resource is required");
        rejectUrlResource(resource);

        if (resource instanceof ClassPathResource) {
            if (!allowClasspathResources) {
                throw untrusted("Classpath document resources are not trusted by policy");
            }
            requireReadable(resource);
            return;
        }

        if (resource instanceof ByteArrayResource || resource instanceof InputStreamResource) {
            if (!allowInMemoryResources) {
                throw untrusted("In-memory document resources are not trusted by policy");
            }
            requireReadable(resource);
            return;
        }

        if (isFileResource(resource)) {
            validateFileResource(resource);
            return;
        }

        throw untrusted("Unsupported document resource type");
    }

    private void rejectUrlResource(Resource resource) {
        String protocol = protocol(resource);
        if (resource instanceof UrlResource || isRemoteProtocol(protocol)) {
            throw untrusted("Remote URL document resources are not trusted");
        }
    }

    private boolean isRemoteProtocol(String protocol) {
        return "http".equals(protocol)
            || "https".equals(protocol)
            || "ftp".equals(protocol);
    }

    private String protocol(Resource resource) {
        try {
            URL url = resource.getURL();
            return url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.ROOT);
        } catch (IOException ex) {
            String description = resource.getDescription();
            if (StringUtils.hasText(description) && description.startsWith("class path resource")) {
                return "classpath";
            }
            return "";
        }
    }

    private boolean isFileResource(Resource resource) {
        if (resource.isFile()) {
            return true;
        }
        return "file".equals(protocol(resource));
    }

    private void validateFileResource(Resource resource) {
        if (trustedRoots.isEmpty()) {
            throw untrusted("File document resources require a trusted root");
        }
        requireReadable(resource);
        try {
            Path file = resource.getFile().toPath().toRealPath();
            boolean trusted = trustedRoots.stream().anyMatch(root -> file.startsWith(realRoot(root)));
            if (!trusted) {
                throw untrusted("File document resource is outside trusted roots");
            }
        } catch (IOException ex) {
            throw new DocumentIngestionException(
                DocumentIngestionFailureCode.DOCUMENT_RESOURCE_UNTRUSTED,
                "Unable to resolve the document resource path",
                ex
            );
        }
    }

    private Path realRoot(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException ex) {
            return root.toAbsolutePath().normalize();
        }
    }

    private void requireReadable(Resource resource) {
        if (!resource.exists() || !resource.isReadable()) {
            throw untrusted("Document resource must exist and be readable");
        }
    }

    private DocumentIngestionException untrusted(String safeMessage) {
        return new DocumentIngestionException(
            DocumentIngestionFailureCode.DOCUMENT_RESOURCE_UNTRUSTED,
            safeMessage
        );
    }

    public static final class Builder {
        private final List<Path> trustedRoots = new ArrayList<>();
        private boolean allowClasspathResources;
        private boolean allowInMemoryResources = true;

        private Builder() {
        }

        public Builder trustedRoot(Path trustedRoot) {
            if (trustedRoot != null) {
                this.trustedRoots.add(trustedRoot);
            }
            return this;
        }

        public Builder trustedRoots(List<Path> trustedRoots) {
            this.trustedRoots.clear();
            if (trustedRoots != null) {
                trustedRoots.stream()
                    .filter(Objects::nonNull)
                    .forEach(this.trustedRoots::add);
            }
            return this;
        }

        public Builder allowClasspathResources(boolean allowClasspathResources) {
            this.allowClasspathResources = allowClasspathResources;
            return this;
        }

        public Builder allowInMemoryResources(boolean allowInMemoryResources) {
            this.allowInMemoryResources = allowInMemoryResources;
            return this;
        }

        public SpringAiTrustedResourcePolicy build() {
            return new SpringAiTrustedResourcePolicy(
                trustedRoots,
                allowClasspathResources,
                allowInMemoryResources
            );
        }
    }
}
