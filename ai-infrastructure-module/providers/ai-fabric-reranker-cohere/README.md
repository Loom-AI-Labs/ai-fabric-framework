# Optional Cohere Reranker

Implements `RerankingProvider` using Cohere's native
[v2 rerank contract](https://docs.cohere.com/reference/rerank). This adapter is
not automatically enabled or included in customer runtime configuration.

The trusted host supplies the exact model, secret supplier and mandatory
`RerankingCallBudget` bridge to its approved accounting scope. There is no
unmetered default, model alias substitution, credential fallback, automatic
retry, redirect or customer-selected destination. Missing or unverifiable native
usage keeps an ambiguous reservation; unknown cost is not zero.

Inputs must be bounded excerpts already authorized by the caller. Only query
and text are sent; internal candidate references and customer/tenant metadata
are not sent. Native result indices map to existing references. Provider-returned
document text is never accepted as evidence. Scores are relevance values, not
answer-correctness probabilities.

Billing units are retained as returned (`search_units` or `input_tokens`, for
example). The host must qualify the chosen model's actual unit/rate contract
before supplying its guard; these units must not be silently interchanged.
The adapter alone is not complete funding integration or production approval.
