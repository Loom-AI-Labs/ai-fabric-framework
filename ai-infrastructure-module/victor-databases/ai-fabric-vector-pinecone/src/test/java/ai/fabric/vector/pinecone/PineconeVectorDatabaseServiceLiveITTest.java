package ai.fabric.vector.pinecone;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PineconeVectorDatabaseServiceLiveITTest {

    @AfterEach
    void clearRequiredFlag() {
        System.clearProperty("pinecone.live.required");
    }

    @Test
    void missingLiveConfigurationSkipsByDefault() {
        System.setProperty("pinecone.live.required", "false");

        assertThat(PineconeVectorDatabaseServiceLiveIT.liveConfigurationRequired()).isFalse();

        assertThatThrownBy(() -> PineconeVectorDatabaseServiceLiveIT.requireLiveConfiguration(false, "missing live config"))
            .isInstanceOf(TestAbortedException.class)
            .hasMessageContaining("missing live config");
    }

    @Test
    void missingLiveConfigurationFailsWhenRequired() {
        System.setProperty("pinecone.live.required", "true");

        assertThat(PineconeVectorDatabaseServiceLiveIT.liveConfigurationRequired()).isTrue();
        assertThatThrownBy(() -> PineconeVectorDatabaseServiceLiveIT.requireLiveConfiguration(false, "missing live config"))
            .isInstanceOf(AssertionFailedError.class)
            .hasMessageContaining("missing live config")
            .hasMessageContaining("Pinecone live verification is required");
    }

    @Test
    void presentLiveConfigurationDoesNotSkipOrFail() {
        System.setProperty("pinecone.live.required", "true");

        assertThatCode(() -> PineconeVectorDatabaseServiceLiveIT.requireLiveConfiguration(true, "present live config"))
            .doesNotThrowAnyException();
    }
}
