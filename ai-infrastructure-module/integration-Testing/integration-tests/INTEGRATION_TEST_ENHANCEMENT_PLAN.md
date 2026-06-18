## Functional Integration Test Expansion Plan

### 1. Real Compound Routing with Error Recovery
- Drive an action-first intent that triggers a handler exception, validate `handleError`, sanitized payload, next-step recommendations, and that vectors remain intact.
- Assert intent history captures the failure and follow-on recovery information intent.

### 2. Multi-Provider Intent Extraction Resilience
- Force anthropic → cohere → openai failover while returning malformed JSON to exercise `IntentQueryExtractor` repair logic.
- Validate provider health telemetry and final orchestrator success, including sanitized payload warnings.

### 3. Smart Suggestions Deep Link Regression
- Produce multiple recommendations targeting different vector spaces.
- Verify enrichment metadata, suggestion prioritisation, retrieved document alignment with `searchable-fields`, and sanitization metadata propagation.

### 4. Action + Vector Lifecycle Synchronisation
- Create entities, execute `remove_vector` and `clear_vector_index`, then rebuild embeddings.
- Confirm vector existence transitions, post-reseed RAG responses, and scheduled TTL cleanup in intent history.

### 5. Cross-Session Intent History Aggregation
- Send sequential queries under distinct session IDs (mixing PII, out-of-scope, and successful intents).
- Assert consolidated history records, encrypted-original toggles, and analytics fields (`intentCount`, `success`).

### 6. Hybrid Retrieval Toggle Validation
- Enable contextual/hybrid flags on `RAGRequest` via information intents.
- Confirm fallback to standard vector search when hybrid services unavailable and metadata flags (`hybridSearchUsed`, `contextualSearchUsed`) reflect behaviour.

### 7. ONNX Fallback Readiness
- Switch embedding provider to ONNX and run information intents.
- Ensure vectors build with local embeddings, RAG context quality remains acceptable, and sanitization rules still apply.

### 8. PII Detection Edge Spectrum
- Craft queries containing email, phone, API key, and secret tokens under both DETECT_ONLY and FORCE_REDACTION configurations.
- Verify sanitized payload variants, guidance toggles, and intent history `hasSensitiveData` coverage.

### 9. Smart Validation & Audit Events
- Trigger flows where smart validation rejects low-confidence intents.
- Ensure orchestrator surfaces actionable guidance, audit events are emitted, and sanitization retains validation error codes when enabled.

### Implementation Notes
- Reuse existing `TestProduct` fixtures, augment with policy/runbook documents as needed.
- Centralise environment setup for API keys and provider toggles to avoid duplication.
- Keep executions targeted (`-Dtest=…`) to comply with “no full suite” requirement.

### Implemented Suites (Current Iteration)
- **Hook orchestration**
  - `EntityAccessPolicyIntegrationTest`
  - `SecurityAnalysisPolicyIntegrationTest`
  - `ComplianceCheckProviderIntegrationTest`
- **Lifecycle & retention**
  - `IntentHistoryLifecycleIntegrationTest`
- **Performance & caching**
  - `AccessControlCachingPerformanceIntegrationTest`
- **Edge / failure handling**
  - `SecurityRateLimitingEdgeCaseIntegrationTest`

Run the focused set with:

```
mvn -Dtest=EntityAccessPolicyIntegrationTest,SecurityAnalysisPolicyIntegrationTest,ComplianceCheckProviderIntegrationTest,IntentHistoryLifecycleIntegrationTest,AccessControlCachingPerformanceIntegrationTest,SecurityRateLimitingEdgeCaseIntegrationTest,AIAccessControlServiceIntegrationTest,AISecurityServiceIntegrationTest,AIComplianceServiceIntegrationTest,RAGOrchestratorIntegrationTest test
```
