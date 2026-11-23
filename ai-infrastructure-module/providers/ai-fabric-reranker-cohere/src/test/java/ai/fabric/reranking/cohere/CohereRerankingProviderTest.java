package ai.fabric.reranking.cohere;

import ai.fabric.spi.RerankingCallBudget;
import ai.fabric.spi.RerankingProvider;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CohereRerankingProviderTest {
    private final Call.Factory factory = mock(Call.Factory.class);
    private final Call call = mock(Call.class);
    private final RerankingCallBudget budget = mock(RerankingCallBudget.class);
    private final RerankingCallBudget.Attempt attempt = mock(RerankingCallBudget.Attempt.class);
    private CohereRerankingProvider provider;

    @BeforeEach
    void setup() {
        when(factory.newCall(any())).thenReturn(call);
        when(call.timeout()).thenReturn(new okio.Timeout());
        when(budget.reserve(any(), any(), any())).thenReturn(attempt);
        when(attempt.id()).thenReturn(UUID.randomUUID());
        provider = new CohereRerankingProvider(() -> "test-credential-never-in-evidence", "pinned-test-model", budget, factory);
    }

    @Test
    void nativeReferencesReorderOnlyExistingApprovedCandidatesAndPreserveUsage() throws Exception {
        when(call.execute()).thenReturn(response(200, """
            {"id":"request-1","results":[
              {"index":1,"relevance_score":0.9,"document":{"text":"provider cannot inject this"}},
              {"index":0,"relevance_score":0.2}],"meta":{"billed_units":{"input_tokens":192}}}
            """));
        var result = provider.rerank(request());
        assertThat(result.candidates()).extracting(RerankingProvider.RankedCandidate::reference).containsExactly("b", "a");
        assertThat(result.usage().billedUnits()).containsEntry("input_tokens", 192L);
        verify(attempt).settle(result.usage(), "request-1");
        verify(attempt, never()).ambiguous(any());
        verify(call, times(1)).execute();
        var outgoing = org.mockito.ArgumentCaptor.forClass(okhttp3.Request.class);
        verify(factory).newCall(outgoing.capture());
        assertThat(outgoing.getValue().url().toString()).isEqualTo("https://api.cohere.com/v2/rerank");
        okio.Buffer body = new okio.Buffer();
        outgoing.getValue().body().writeTo(body);
        String json = body.readUtf8();
        assertThat(json).contains("approved one", "approved two", "max_tokens_per_doc", "pinned-test-model")
            .doesNotContain("test-credential", "tenantId", "customerId", "reference");
    }

    @Test
    void denialCannotReachTheNativeTransport() throws Exception {
        when(budget.reserve(any(), any(), any())).thenThrow(new SecurityException("funding denied"));
        assertThatThrownBy(() -> provider.rerank(request())).isInstanceOf(SecurityException.class);
        verify(call, never()).execute();
        verify(attempt, never()).settle(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"results\":[{\"index\":2,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}],\"meta\":{\"billed_units\":{\"search_units\":1}}}",
        "{\"results\":[{\"index\":0,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}],\"meta\":{\"billed_units\":{\"search_units\":1}}}",
        "{\"results\":[{\"index\":1.5,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}],\"meta\":{\"billed_units\":{\"search_units\":1}}}",
        "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}",
        "{\"results\":[],\"meta\":{\"billed_units\":{\"search_units\":1}}}",
        "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}],\"meta\":{\"billed_units\":{}}}"
    })
    void malformedIdentityOrUnknownUsageRetainsReservationRatherThanFabricatingSuccess(String json) throws Exception {
        when(call.execute()).thenReturn(response(200, json));
        assertThatThrownBy(() -> provider.rerank(request())).hasMessageContaining("did not complete");
        verify(attempt).ambiguous("COHERE_NATIVE_OUTCOME_OR_ACCOUNTING_UNVERIFIED");
        verify(attempt, never()).settle(any(), any());
        verify(call, times(1)).execute();
    }

    @Test
    void authenticationFailureIsNotRetriedOrReportedFreeAndDoesNotExposeResponseBody() throws Exception {
        when(call.execute()).thenReturn(response(401, "{\"message\":\"private-canary-do-not-expose\"}"));
        assertThatThrownBy(() -> provider.rerank(request()))
            .hasMessageNotContaining("private-canary").hasNoCause()
            .isNotInstanceOf(ai.fabric.spi.RerankingUnavailableException.class);
        verify(attempt).ambiguous(any());
        verify(call, times(1)).execute();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 502, 503, 504})
    void transientHttpIsTypedButStillHeldAndNeverRetried(int status) throws Exception {
        when(call.execute()).thenReturn(response(status, "{\"message\":\"private-canary\"}"));
        assertThatThrownBy(() -> provider.rerank(request())).isInstanceOf(ai.fabric.spi.RerankingUnavailableException.class)
            .hasMessageNotContaining("private-canary").hasNoCause();
        verify(attempt).ambiguous(any());
        verify(call, times(1)).execute();
        verify(attempt, never()).settle(any(), any());
    }

    @Test
    void missingCredentialOrAccountingPreventsSending() throws Exception {
        var absent = new CohereRerankingProvider(() -> "", "pinned-test-model", budget, factory);
        assertThatThrownBy(() -> absent.rerank(request())).hasMessageContaining("not configured");
        verify(call, never()).execute();
        verify(budget, never()).reserve(any(), any(), any());
        assertThatThrownBy(() -> new CohereRerankingProvider(() -> "key", "model", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void enforcesUniqueReferencesCandidateTextAndFiniteDeadline() {
        assertThatThrownBy(() -> new RerankingProvider.Request("q", List.of(
            new RerankingProvider.Candidate("a", "one"), new RerankingProvider.Candidate("a", "two")), Duration.ofSeconds(5), 1024))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankingProvider.Candidate("a", "x".repeat(16001))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RerankingProvider.Request("q", request().candidates(), Duration.ofMinutes(1), 1024))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static RerankingProvider.Request request() {
        return new RerankingProvider.Request("question", List.of(new RerankingProvider.Candidate("a", "approved one"),
            new RerankingProvider.Candidate("b", "approved two")), Duration.ofSeconds(5), 1024);
    }

    private static Response response(int status, String json) {
        return new Response.Builder().request(new okhttp3.Request.Builder().url("https://api.cohere.com/v2/rerank").build())
            .protocol(Protocol.HTTP_1_1).code(status).message("test")
            .body(ResponseBody.create(json, MediaType.get("application/json"))).build();
    }
}
