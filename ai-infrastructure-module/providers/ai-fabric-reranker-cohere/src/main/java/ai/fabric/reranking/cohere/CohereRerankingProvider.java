package ai.fabric.reranking.cohere;

import ai.fabric.spi.RerankingCallBudget;
import ai.fabric.spi.RerankingProvider;
import ai.fabric.spi.RerankingUnavailableException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Optional, explicitly constructed adapter. No auto-enablement, retries, redirects or credential fallback. */
public final class CohereRerankingProvider implements RerankingProvider {
    private static final String ENDPOINT = "https://api.cohere.com/v2/rerank";
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final Supplier<String> apiKey;
    private final String model;
    private final RerankingCallBudget budget;
    private final Call.Factory transport;
    private final Semaphore concurrent = new Semaphore(2);
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public CohereRerankingProvider(Supplier<String> apiKey, String model, RerankingCallBudget budget) {
        this(apiKey, model, budget, new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(Duration.ofSeconds(5)).build());
    }

    // Package-private transport injection for native-contract tests, never a configurable destination.
    CohereRerankingProvider(Supplier<String> apiKey, String model, RerankingCallBudget budget, Call.Factory transport) {
        this.apiKey = Objects.requireNonNull(apiKey);
        if (model == null || model.isBlank() || model.length() > 128) throw new IllegalArgumentException("Explicit reranker model is required");
        this.model = model;
        this.budget = Objects.requireNonNull(budget, "Reranking accounting admission is required");
        this.transport = Objects.requireNonNull(transport);
    }

    @Override public String providerId() { return "cohere"; }
    @Override public String modelId() { return model; }
    @Override public String toString() { return "CohereRerankingProvider[model=" + model + "]"; }

    @Override
    public Result rerank(RerankingProvider.Request input) {
        Objects.requireNonNull(input);
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        if (!concurrent.tryAcquire()) throw new RerankingUnavailableException(RerankingUnavailableException.Reason.CAPACITY);
        RerankingCallBudget.Attempt attempt = null;
        boolean dispatched = false;
        try {
            String key = apiKey.get();
            if (key == null || key.isBlank()) throw new IllegalStateException("Cohere credential is not configured");
            var payload = mapper.createObjectNode().put("model", model).put("query", input.query())
                .put("top_n", input.candidates().size()).put("max_tokens_per_doc", input.maxTokensPerDocument());
            var texts = payload.putArray("documents");
            input.candidates().forEach(candidate -> texts.add(candidate.text()));
            byte[] bytes = mapper.writeValueAsBytes(payload);
            var request = new okhttp3.Request.Builder().url(ENDPOINT).header("Authorization", "Bearer " + key)
                .header("X-Client-Name", "ai-fabric-reranker").header("Accept-Encoding", "identity")
                .post(RequestBody.create(bytes, MediaType.get("application/json"))).build();
            Call call = transport.newCall(request);
            call.timeout().timeout(input.timeout().toMillis(), TimeUnit.MILLISECONDS);
            attempt = Objects.requireNonNull(budget.reserve(providerId(), model, input), "Reranking reservation is required");
            Objects.requireNonNull(attempt.id(), "Reranking attempt identity is required");
            dispatched = true;
            try (Response response = call.execute()) {
                if (java.util.Set.of(408, 429, 502, 503, 504).contains(response.code())) {
                    throw new RerankingUnavailableException(RerankingUnavailableException.Reason.TRANSIENT_HTTP);
                }
                if (!response.isSuccessful() || response.body() == null || response.body().contentType() == null
                    || !"application".equals(response.body().contentType().type())
                    || !"json".equals(response.body().contentType().subtype())) {
                    throw new IllegalStateException("Cohere native response was not successful JSON");
                }
                byte[] body = response.body().byteStream().readNBytes(MAX_RESPONSE_BYTES + 1);
                if (body.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("Cohere response exceeded its bound");
                Result result = decode(input, mapper.readTree(body));
                attempt.settle(result.usage(), result.providerRequestId());
                return result;
            }
        } catch (Exception failure) {
            if (attempt != null && dispatched) {
                try { attempt.ambiguous("COHERE_NATIVE_OUTCOME_OR_ACCOUNTING_UNVERIFIED"); }
                catch (RuntimeException accountingUnavailable) { /* The original reservation must remain held. */ }
            }
            if (!dispatched && failure instanceof RuntimeException denied) throw denied;
            if (failure instanceof RerankingUnavailableException unavailable) throw unavailable;
            if (!Thread.currentThread().isInterrupted() && failure instanceof java.io.InterruptedIOException) {
                throw new RerankingUnavailableException(RerankingUnavailableException.Reason.TIMEOUT);
            }
            if (failure instanceof java.net.ConnectException || failure instanceof java.net.UnknownHostException) {
                throw new RerankingUnavailableException(RerankingUnavailableException.Reason.CONNECTION_UNAVAILABLE);
            }
            // Do not copy provider bodies, headers, credentials or candidate text into diagnostics.
            throw new IllegalStateException("Cohere reranking did not complete with verified results and usage");
        } finally {
            concurrent.release();
        }
    }

    private Result decode(RerankingProvider.Request request, JsonNode response) {
        JsonNode results = response.path("results");
        if (!results.isArray() || results.size() != request.candidates().size()) throw invalid();
        var seen = new HashSet<Integer>();
        var ranked = new ArrayList<RankedCandidate>();
        for (JsonNode row : results) {
            JsonNode index = row.path("index"), score = row.path("relevance_score");
            if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() < 0
                || index.intValue() >= request.candidates().size() || !seen.add(index.intValue())
                || !score.isNumber() || !Double.isFinite(score.doubleValue())) throw invalid();
            ranked.add(new RankedCandidate(request.candidates().get(index.intValue()).reference(), score.doubleValue()));
        }
        JsonNode billed = response.path("meta").path("billed_units");
        if (!billed.isObject()) throw invalid();
        Map<String, Long> units = new LinkedHashMap<>();
        billed.fields().forEachRemaining(field -> {
            if (!field.getKey().matches("[a-z_]{1,64}") || !field.getValue().isIntegralNumber()
                || !field.getValue().canConvertToLong() || field.getValue().longValue() < 0) throw invalid();
            units.put(field.getKey(), field.getValue().longValue());
        });
        String requestId = response.path("id").asText("");
        if (requestId.length() > 128 || (!requestId.isBlank() && !requestId.matches("[A-Za-z0-9._:-]+"))) throw invalid();
        return new Result(ranked, new Usage(units), requestId);
    }

    private static IllegalStateException invalid() { return new IllegalStateException("Invalid native reranking receipt"); }
}
