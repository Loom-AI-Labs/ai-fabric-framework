# GitHub Actions Integration Guide

This guide documents how the relationship-query RealAPI suite is run from
`.github/workflows/integration-tests-manual.yml`.

## Current Contract

The workflow is secret-first:

- Provider credentials should live in GitHub Actions secrets such as `OPENAI_API_KEY`,
  `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `COHERE_API_KEY`, `AZURE_API_KEY`, and `PINECONE_API_KEY`.
- Pinecone location should be supplied through repository variables or workflow inputs:
  `PINECONE_API_HOST`, or both `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT`.
- Workflow-dispatch key inputs are still accepted for ad hoc manual runs, but they are explicitly
  masked and should not be the normal release path.
- The framework build step runs normal unit tests before the RealAPI suite.

The script itself remains provider-matrix driven:

```bash
bash run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
bash run-relationship-query-realapi-tests.sh "anthropic:onnx:milvus"
```

The matrix format is:

```text
<llm-provider>:<embedding-provider>:<vector-database>
```

The vector database segment is optional for local runs.

## Workflow Example

The maintained workflow shape is:

```yaml
- name: Run Relationship Query Integration Tests (Real API)
  run: |
    cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
    bash run-relationship-query-realapi-tests.sh "${{ github.event.inputs.llm_provider }}:${{ github.event.inputs.embedding_provider }}:${{ github.event.inputs.vector_database }}"
  env:
    OPENAI_API_KEY: ${{ github.event.inputs.openai_api_key || secrets.OPENAI_API_KEY }}
    ANTHROPIC_API_KEY: ${{ github.event.inputs.anthropic_api_key || secrets.ANTHROPIC_API_KEY }}
    GEMINI_API_KEY: ${{ github.event.inputs.gemini_api_key || secrets.GEMINI_API_KEY }}
    COHERE_API_KEY: ${{ github.event.inputs.cohere_api_key || secrets.COHERE_API_KEY }}
    AZURE_API_KEY: ${{ github.event.inputs.azure_api_key || secrets.AZURE_API_KEY }}
    PINECONE_API_KEY: ${{ github.event.inputs.pinecone_api_key || secrets.PINECONE_API_KEY }}
    PINECONE_API_HOST: ${{ github.event.inputs.pinecone_host || vars.PINECONE_API_HOST }}
    PINECONE_INDEX_NAME: ${{ github.event.inputs.pinecone_index_name || vars.PINECONE_INDEX_NAME }}
    PINECONE_ENVIRONMENT: ${{ github.event.inputs.pinecone_environment || vars.PINECONE_ENVIRONMENT }}
    AI_INFRASTRUCTURE_PERSISTENCE_DATABASE: ${{ github.event.inputs.persistence_database }}
```

The workflow validates Pinecone early when `vector_database=pinecone`: either
`PINECONE_API_HOST` must be set, or both `PINECONE_INDEX_NAME` and `PINECONE_ENVIRONMENT` must be
set. This avoids long Spring startup failures and avoids hardcoded Pinecone resource hosts in the
repository.

## Environment Resolution

The test profile supports explicit environment selection:

```yaml
ai:
  providers:
    llm-provider: ${AI_INFRASTRUCTURE_LLM_PROVIDER:${LLM_PROVIDER:openai}}
    embedding-provider: ${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:${EMBEDDING_PROVIDER:onnx}}
  vector-db:
    type: ${AI_INFRASTRUCTURE_VECTOR_DATABASE:${VECTOR_DB:lucene}}
```

The script exports `AI_INFRASTRUCTURE_LLM_PROVIDER`,
`AI_INFRASTRUCTURE_EMBEDDING_PROVIDER`, and `AI_INFRASTRUCTURE_VECTOR_DATABASE` from the matrix
argument unless the caller has already set them.

## Local Development

For local runs, export provider credentials in your shell or rely on the local-only
`BackendEnvTestConfiguration` loader when your development tree has a backend `.env` file:

```bash
export OPENAI_API_KEY=...
./run-relationship-query-realapi-tests.sh "openai:onnx:memory"
```

The local `.env` loader is a developer convenience only. GitHub Actions should use secrets and
repository variables.

## Supported Providers

LLM providers:

- `openai`
- `anthropic`
- `gemini`
- `cohere`
- `azure`

Embedding providers:

- `onnx`
- `openai`
- `gemini`
- `cohere`
- `azure`

Vector databases:

- `lucene`
- `memory`
- `pinecone`
- `qdrant`
- `weaviate`
- `milvus`

For Qdrant, Weaviate, and Milvus, the manual workflow enables the `testcontainers` Spring profile.
For Pinecone, the workflow expects a real SaaS index configured through secrets and variables.

## Troubleshooting

If tests fail with "API key not configured", check that the matching GitHub secret or ad hoc
workflow input is present.

If Pinecone fails before Maven starts, set `PINECONE_API_HOST` or set both `PINECONE_INDEX_NAME` and
`PINECONE_ENVIRONMENT`.

If the wrong provider is used, confirm the matrix argument and any pre-set
`AI_INFRASTRUCTURE_*` variables:

```bash
bash run-relationship-query-realapi-tests.sh "${LLM_PROVIDER}:${EMBEDDING_PROVIDER}:${VECTOR_DB}"
```

## Related Documentation

- [REALAPI_TESTS.md](REALAPI_TESTS.md)
- [run-relationship-query-realapi-tests.sh](run-relationship-query-realapi-tests.sh)
- [.github/workflows/integration-tests-manual.yml](../../../.github/workflows/integration-tests-manual.yml)
