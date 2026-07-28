package ai.fabric.intent.action.invocation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class GovernedActionInvocationArchitectureTest {

    private static final Path PRODUCTION_SOURCES = Path.of("src/main/java");
    private static final Path GOVERNED_INVOKER = Path.of(
        "ai/fabric/intent/action/invocation/DefaultGovernedActionInvocationService.java"
    );

    @Test
    void productionCodeMustNotBypassGovernedActionInvocation() throws IOException {
        assertThat(PRODUCTION_SOURCES).isDirectory();

        List<String> bypasses;
        try (var sources = Files.walk(PRODUCTION_SOURCES)) {
            bypasses = sources
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> !PRODUCTION_SOURCES.relativize(path).equals(GOVERNED_INVOKER))
                .filter(path -> containsDirectHandlerInvocation(path))
                .map(path -> PRODUCTION_SOURCES.relativize(path).toString())
                .sorted()
                .toList();
        }

        assertThat(bypasses)
            .as("AIActionHandler.executeAction must only be called by the governed invoker")
            .isEmpty();
    }

    private boolean containsDirectHandlerInvocation(Path source) {
        try {
            return Files.readString(source).contains(".executeAction(");
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to inspect " + source, ex);
        }
    }
}
