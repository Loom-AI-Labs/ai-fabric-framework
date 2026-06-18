package ai.fabric.storage.auto;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TableAutoCreationServiceTest {

    @Test
    void unsupportedDatabaseTypeFailsAsInvalidConfiguration() throws Exception {
        TableAutoCreationService service = new TableAutoCreationService(mock(DataSource.class));
        Method generator = TableAutoCreationService.class
            .getDeclaredMethod("generateCreateIndexingQueueSQL", String.class, String.class);
        generator.setAccessible(true);

        assertThatThrownBy(() -> generator.invoke(service, "ORACLE", "ai_indexing_queue"))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasRootCauseMessage("Database ORACLE not supported for indexing queue auto-create.");
    }
}
