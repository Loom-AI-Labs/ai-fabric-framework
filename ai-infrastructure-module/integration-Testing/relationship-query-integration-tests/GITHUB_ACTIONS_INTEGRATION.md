# GitHub Actions Integration Guide

## Overview

The `run-relationship-query-realapi-tests.sh` script fully supports GitHub Actions workflow inputs through multiple configuration methods.

**Key Points:**
- ✅ **GitHub Actions**: API key provided via workflow input `github.event.inputs.openai_api_key`
- ✅ **Local Development**: API key auto-loaded from `backend/.env` via `BackendEnvTestConfiguration`
- ⚠️ **Important**: .env auto-loading is **local-only** - GitHub Actions requires explicit workflow input

## How It Works

### Configuration Priority (Highest to Lowest)

1. **Script Arguments** - Direct provider matrix specification
2. **Environment Variables (GitHub Actions)** - Workflow inputs
3. **Defaults** - From `application-realapi.yml`

### GitHub Actions Workflow

The script is integrated into `.github/workflows/integration-tests-manual.yml`:

```yaml
- name: Run Relationship Query Integration Tests (Real API)
  run: |
    cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
    bash run-relationship-query-realapi-tests.sh "${{ github.event.inputs.llm_provider }}:${{ github.event.inputs.embedding_provider }}:${{ github.event.inputs.vector_database }}"
  env:
    OPENAI_API_KEY: ${{ github.event.inputs.openai_api_key }}
    AI_INFRASTRUCTURE_PERSISTENCE_DATABASE: ${{ github.event.inputs.persistence_database }}
```

## Workflow Inputs

The workflow accepts the following inputs:

| Input | Type | Default | Options |
|-------|------|---------|---------|
| `openai_api_key` | string | (required) | Your OpenAI API key |
| `llm_provider` | choice | `openai` | openai, azure-openai, cohere, anthropic |
| `embedding_provider` | choice | `onnx` | onnx, openai, azure-openai |
| `vector_database` | choice | `lucene` | lucene, pinecone, weaviate, qdrant, milvus, memory |
| `persistence_database` | choice | `h2` | h2, postgresql |
| `modules` | choice | `all` | all, ai-fabric, relationship-query |
| `timeout_minutes` | string | `30` | Number of minutes |

## Script Behavior with GitHub Actions

### 1. Matrix Spec from Arguments

When GitHub Actions passes the provider matrix as a script argument:

```bash
bash run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
```

The script parses it and exports:
```bash
AI_INFRASTRUCTURE_LLM_PROVIDER=openai
AI_INFRASTRUCTURE_EMBEDDING_PROVIDER=onnx
AI_INFRASTRUCTURE_VECTOR_DATABASE=lucene
```

### 2. Environment Variables from Workflow

If environment variables are already set by the workflow:

```yaml
env:
  AI_INFRASTRUCTURE_LLM_PROVIDER: openai
  AI_INFRASTRUCTURE_EMBEDDING_PROVIDER: onnx
  AI_INFRASTRUCTURE_VECTOR_DATABASE: memory
```

The script respects them and won't override:

```bash
# Script logic (lines 122-123)
export AI_INFRASTRUCTURE_LLM_PROVIDER="${AI_INFRASTRUCTURE_LLM_PROVIDER:-$LLM_PROVIDER}"
export AI_INFRASTRUCTURE_EMBEDDING_PROVIDER="${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:-$EMBEDDING_PROVIDER}"
```

### 3. Hybrid Approach (Current)

Our workflow uses **both** for maximum flexibility:

```yaml
bash run-relationship-query-realapi-tests.sh "${{ github.event.inputs.llm_provider }}:${{ github.event.inputs.embedding_provider }}:${{ github.event.inputs.vector_database }}"
env:
  OPENAI_API_KEY: ${{ github.event.inputs.openai_api_key }}
```

This ensures:
- ✅ Provider matrix is explicit in script arguments
- ✅ API key is securely passed via environment (from workflow input, not .env file)
- ✅ Consistent with `integration-tests` pattern

**Important Note:**
- The `BackendEnvTestConfiguration` that auto-loads from `backend/.env` is **only for local development**
- In GitHub Actions, the API key **must be provided** via workflow inputs
- The configuration will **not** attempt to load from .env files in CI/CD environments

## Test Execution Flow

### Local Development

```bash
# OpenAI key auto-loaded from backend/.env via BackendEnvTestConfiguration
./run-relationship-query-realapi-tests.sh "openai:onnx:memory"
```

### GitHub Actions (API Key from Workflow Input)

```
GitHub Workflow Dispatch
    ↓
User selects inputs:
  - llm_provider: openai
  - embedding_provider: onnx
  - vector_database: lucene
  - openai_api_key: sk-***
    ↓
Workflow runs:
  bash run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
    ↓
Script exports:
  AI_INFRASTRUCTURE_LLM_PROVIDER=openai
  AI_INFRASTRUCTURE_EMBEDDING_PROVIDER=onnx
  AI_INFRASTRUCTURE_VECTOR_DATABASE=lucene
  OPENAI_API_KEY=sk-***
    ↓
Maven Failsafe picks up env vars
    ↓
Spring Boot loads application-realapi.yml with placeholders:
  llm-provider: ${AI_INFRASTRUCTURE_LLM_PROVIDER:${LLM_PROVIDER:openai}}
    ↓
Tests run with selected providers
```

## Example: Running Tests in GitHub Actions

### Step 1: Navigate to Actions Tab

Go to your repository → Actions → "Integration Tests (Manual Trigger)"

### Step 2: Click "Run workflow"

### Step 3: Fill in inputs

```yaml
Modules: relationship-query
OpenAI API Key: sk-proj-your-key-here
LLM Provider: openai
Embedding Provider: onnx
Vector Database: memory
Persistence Database: h2
Timeout: 30
```

### Step 4: Click "Run workflow"

The workflow will:
1. ✅ Check out code
2. ✅ Set up Java 21
3. ✅ Cache Maven dependencies
4. ✅ Clean temporary files
5. ✅ Build AI Fabric Framework (once - in workflow)
6. ✅ Run relationship query tests with: `openai:onnx:memory` (skips duplicate build)
7. ✅ Upload test reports
8. ✅ Publish test results

**Note:** The script detects `CI=true` or `GITHUB_ACTIONS=true` environment variables and skips its internal dependency build check, avoiding duplicate builds.

## Environment Variable Resolution

The script uses nested fallbacks for maximum flexibility:

```yaml
# From application-realapi.yml
ai:
  providers:
    # Priority: AI_INFRASTRUCTURE_LLM_PROVIDER > LLM_PROVIDER > "openai"
    llm-provider: ${AI_INFRASTRUCTURE_LLM_PROVIDER:${LLM_PROVIDER:openai}}

    # Priority: AI_INFRASTRUCTURE_EMBEDDING_PROVIDER > EMBEDDING_PROVIDER > "onnx"
    embedding-provider: ${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:${EMBEDDING_PROVIDER:onnx}}

  vector-db:
    # Priority: AI_INFRASTRUCTURE_VECTOR_DATABASE > VECTOR_DB > "lucene"
    type: ${AI_INFRASTRUCTURE_VECTOR_DATABASE:${VECTOR_DB:lucene}}
```

## Supported Provider Combinations in GitHub Actions

### LLM Providers
- ✅ **openai** (default) - GPT-4o-mini, GPT-4, GPT-3.5
- ⚠️ **anthropic** - Requires ANTHROPIC_API_KEY
- ⚠️ **azure-openai** - Requires Azure credentials
- ⚠️ **cohere** - Requires COHERE_API_KEY
- ⚠️ **openai-compatible** - Custom OpenAI-compatible endpoint

### Embedding Providers
- ✅ **onnx** (default) - Local all-MiniLM-L6-v2 model (384 dimensions)
- ✅ **openai** - text-embedding-3-small (1536 dimensions)
- ⚠️ **azure-openai** - Requires Azure credentials

### Vector Databases
- ✅ **lucene** (default) - Local file-based vector index
- ✅ **memory** - In-memory vector storage (fastest for tests)
- ⚠️ **pinecone** - Requires PINECONE_API_KEY
- ⚠️ **qdrant** - Requires Qdrant instance
- ⚠️ **weaviate** - Requires Weaviate instance
- ⚠️ **milvus** - Requires Milvus instance

**Legend:**
- ✅ = Works out of the box
- ⚠️ = Requires additional configuration/credentials

## Build Optimization

The script intelligently avoids duplicate builds in CI/CD:

**Local Development:**
```bash
# Script checks if dependencies are built
# If not found, auto-builds with: mvn clean install -DskipTests
./run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
```

**GitHub Actions:**
```bash
# Workflow pre-builds dependencies in "Build AI Fabric Framework" step
# Script detects CI environment and skips build check
# Environment variables: CI=true or GITHUB_ACTIONS=true
./run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
```

This prevents duplicate builds and speeds up CI/CD execution time significantly.

## CI/CD Best Practices

### 1. Use Secrets for API Keys

Store sensitive credentials in GitHub Secrets:

```yaml
env:
  OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}
  ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}
  PINECONE_API_KEY: ${{ secrets.PINECONE_API_KEY }}
```

### 2. Matrix Strategy for Multiple Combinations

Test multiple provider combinations in parallel:

```yaml
strategy:
  matrix:
    providers:
      - "openai:onnx:lucene"
      - "openai:onnx:memory"
      - "openai:openai:pinecone"
steps:
  - run: bash run-relationship-query-realapi-tests.sh "${{ matrix.providers }}"
```

### 3. Conditional Execution

Skip provider combinations that aren't configured:

```yaml
- name: Run with Pinecone
  if: ${{ secrets.PINECONE_API_KEY != '' }}
  run: bash run-relationship-query-realapi-tests.sh "openai:onnx:pinecone"
```

## Troubleshooting

### Issue: Tests fail with "API key not configured"

**Solution:** Ensure `openai_api_key` input is provided in workflow dispatch.

### Issue: Wrong provider is used

**Solution:** Check that script argument matches workflow inputs:
```bash
bash run-relationship-query-realapi-tests.sh "${{ github.event.inputs.llm_provider }}:${{ github.event.inputs.embedding_provider }}:${{ github.event.inputs.vector_database }}"
```

### Issue: Environment variables not picked up

**Solution:** The script uses this precedence (lines 122-123):
```bash
# Won't override if already set by GitHub Actions
export AI_INFRASTRUCTURE_LLM_PROVIDER="${AI_INFRASTRUCTURE_LLM_PROVIDER:-$LLM_PROVIDER}"
```

## Summary

| Feature | Status | Notes |
|---------|--------|-------|
| **GitHub Actions Integration** | ✅ Fully Supported | Via workflow_dispatch inputs |
| **Environment Variables** | ✅ Fully Supported | AI_INFRASTRUCTURE_* and short-form |
| **Script Arguments** | ✅ Fully Supported | Matrix spec format |
| **API Key in GitHub Actions** | ✅ Workflow Input | Via github.event.inputs.openai_api_key |
| **API Key in Local Dev** | ✅ Auto-loaded | From backend/.env via BackendEnvTestConfiguration |
| **Multiple Providers** | ✅ Fully Supported | LLM, embedding, vector DB combinations |
| **Consistent with integration-tests** | ✅ Yes | Same pattern and approach |

## Related Documentation

- [REALAPI_TESTS.md](REALAPI_TESTS.md) - Comprehensive test documentation
- [run-relationship-query-realapi-tests.sh](run-relationship-query-realapi-tests.sh) - Script source
- [.github/workflows/integration-tests-manual.yml](../../.github/workflows/integration-tests-manual.yml) - Workflow configuration
