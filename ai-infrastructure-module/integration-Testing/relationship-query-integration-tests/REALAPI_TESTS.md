# Relationship Query Integration Tests - RealAPI Separation

## Overview

Similar to the **ai-infrastructure-module/integration-Testing/integration-tests**, the **relationship-query-integration-tests** module now separates:

- ✅ **Non-RealAPI Tests** - Run during standard `mvn verify` (no API keys needed)
- 🔑 **RealAPI Tests** - Run separately with OpenAI API key via manual trigger

---

## Test Structure

### Directory Layout

```
relationship-query-integration-tests/
├── src/test/java/ai/fabric/relationship/it/
│   ├── RelationshipQueryBasicIntegrationTest.java        (✅ Non-RealAPI)
│   ├── config/
│   │   └── BackendEnvTestConfiguration.java
│   └── realapi/
│       ├── LawFirmRealApiIntegrationTest.java            (🔑 RealAPI)
│       ├── FinancialFraudRealApiIntegrationTest.java     (🔑 RealAPI)
│       └── ECommerceRealApiIntegrationTest.java          (🔑 RealAPI)
└── run-relationship-query-realapi-tests.sh
```

---

## Test Categories

### ✅ Non-RealAPI Tests (Run in `mvn verify`)

**Class:** `RelationshipQueryBasicIntegrationTest.java`
- **Profile:** `@ActiveProfiles("test")` (no real API calls)
- **Uses:** Mocked AI services
- **Database:** H2 in-memory
- **No Dependencies:** Works without OPENAI_API_KEY
- **Tests:**
  - `shouldCreateProductsSuccessfully()`
  - `shouldRetrieveProductsByName()`
  - `shouldFilterProductsByPrice()`
  - `shouldHandleProductUpdates()`
  - `shouldHandleProductDeletion()`

### 🔑 RealAPI Tests (Excluded from `mvn verify`)

**Classes:**
- `LawFirmRealApiIntegrationTest.java` - Legal contract relationships
- `FinancialFraudRealApiIntegrationTest.java` - Transaction fraud detection
- `ECommerceRealApiIntegrationTest.java` - Product relationships

**Profile:** `@ActiveProfiles("realapi")`
- **Requires:** OPENAI_API_KEY environment variable
- **Uses:** Real OpenAI API (GPT-4o-mini)
- **Database:** H2 in-memory
- **Callable via:** Manual scripts or CI/CD with API key

---

## Maven Configuration Changes

### pom.xml Updates

#### 1. Maven Surefire Plugin (Unit Tests)
```xml
<excludes>
    <exclude>**/*IntegrationTest.java</exclude>
    <exclude>**/*IT.java</exclude>
    <exclude>**/*RealApiIntegrationTest.java</exclude>
</excludes>
```

#### 2. Maven Failsafe Plugin (Integration Tests)
```xml
<includes>
    <include>**/*IT.java</include>
    <include>**/*IntegrationTest.java</include>
</includes>
<excludes>
    <!-- Exclude RealAPI tests - they require OpenAI API key -->
    <exclude>**/realapi/*RealApiIntegrationTest.java</exclude>
</excludes>
```

#### 3. RealAPI Tests Profile
```xml
<profile>
    <id>realapi</id>
    <build>
        <plugins>
            <plugin>
                <artifactId>maven-failsafe-plugin</artifactId>
                <includes>
                    <include>**/realapi/*RealApiIntegrationTest.java</include>
                </includes>
                <excludes combine.self="override">
                    <!-- No excludes when running RealAPI tests -->
                </excludes>
            </plugin>
        </plugins>
    </build>
</profile>
```

---

## How to Run Tests

### 1. Standard Verify (No API Key Needed)
```bash
cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
mvn clean verify
```
✅ Runs: `RelationshipQueryBasicIntegrationTest`
❌ Skips: RealAPI tests

### 2. Run RealAPI Tests (Requires API Key)

#### Basic Usage (Default: openai:onnx)
```bash
cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
export OPENAI_API_KEY='sk-proj-...'
bash run-relationship-query-realapi-tests.sh
```

#### Flexible Provider Matrix Configuration

The tests now support flexible provider combinations similar to `integration-tests`:

**LLM Providers:** `openai`, `anthropic`, `azure`, `ollama`
**Embedding Providers:** `openai`, `onnx`, `azure`
**Vector Databases:** `lucene`, `memory`, `pinecone`, `qdrant`

**Examples:**

```bash
# OpenAI LLM + ONNX Embeddings + Lucene Vector DB (default)
./run-relationship-query-realapi-tests.sh "openai:onnx:lucene"

# OpenAI LLM + ONNX Embeddings + In-Memory Vector DB
./run-relationship-query-realapi-tests.sh "openai:onnx:memory"

# Anthropic LLM + OpenAI Embeddings + Pinecone Vector DB
./run-relationship-query-realapi-tests.sh "anthropic:openai:pinecone"

# OpenAI LLM + ONNX Embeddings with separate Vector DB argument
./run-relationship-query-realapi-tests.sh "openai:onnx" "qdrant"
```

**Environment Variable Support:**

You can also set providers via environment variables:

```bash
export AI_INFRASTRUCTURE_LLM_PROVIDER=openai
export AI_INFRASTRUCTURE_EMBEDDING_PROVIDER=onnx
export AI_INFRASTRUCTURE_VECTOR_DATABASE=lucene
./run-relationship-query-realapi-tests.sh
```

Or using the shorter form:

```bash
export LLM_PROVIDER=openai
export EMBEDDING_PROVIDER=onnx
export VECTOR_DB=memory
./run-relationship-query-realapi-tests.sh
```

**Configuration Resolution Order:**

1. Script arguments (`./run-relationship-query-realapi-tests.sh "openai:onnx:lucene"`)
2. `AI_INFRASTRUCTURE_*` environment variables
3. Short-form environment variables (`LLM_PROVIDER`, `EMBEDDING_PROVIDER`, `VECTOR_DB`)
4. Defaults from `application-realapi.yml` (openai:onnx:lucene)

✅ Runs: All `*RealApiIntegrationTest.java` tests
- LawFirmRealApiIntegrationTest
- FinancialFraudRealApiIntegrationTest
- ECommerceRealApiIntegrationTest

### 3. Parent Module Verify
```bash
cd ai-infrastructure-module
mvn clean verify
```
✅ Runs: All modules including relationship-query integration tests (non-RealAPI only)

---

## Configuration Files

### application-realapi.yml

The RealAPI profile configuration supports flexible provider selection via environment variables:

```yaml
ai:
  providers:
    # LLM Provider (AI_INFRASTRUCTURE_LLM_PROVIDER or LLM_PROVIDER)
    llm-provider: ${AI_INFRASTRUCTURE_LLM_PROVIDER:${LLM_PROVIDER:openai}}

    # Embedding Provider (AI_INFRASTRUCTURE_EMBEDDING_PROVIDER or EMBEDDING_PROVIDER)
    embedding-provider: ${AI_INFRASTRUCTURE_EMBEDDING_PROVIDER:${EMBEDDING_PROVIDER:onnx}}

    openai:
      api-key: ${OPENAI_API_KEY:}
      model: ${OPENAI_MODEL:gpt-4o-mini}
      embedding-model: ${OPENAI_EMBEDDING_MODEL:text-embedding-3-small}

    onnx:
      enabled: true
      model-path: ${ONNX_MODEL_PATH:classpath:/models/embeddings/all-MiniLM-L6-v2.onnx}

  vector-db:
    # Vector Database (AI_INFRASTRUCTURE_VECTOR_DATABASE or VECTOR_DB)
    type: ${AI_INFRASTRUCTURE_VECTOR_DATABASE:${VECTOR_DB:lucene}}

    lucene:
      index-path: ${AI_LUCENE_INDEX_PATH:${java.io.tmpdir}/relationship-query-realapi-lucene}

    memory:
      enabled: true

    pinecone:
      api-key: ${PINECONE_API_KEY:}
      index-name: ${PINECONE_INDEX_NAME:}
      api-host: ${PINECONE_API_HOST:}
      environment: ${PINECONE_ENVIRONMENT:}
```

### BackendEnvTestConfiguration (Local Development Only)

The `BackendEnvTestConfiguration` class is designed for **local development convenience**:

```java
@Value("${relationship-test.backend-env-path:../../backend/.env}")
private String backendEnvPath;

@PostConstruct
void loadBackendEnv() {
    // Only loads if environment variable not already set
    if (System.getenv(key) == null && System.getProperty(key) == null) {
        System.setProperty(key, value);
    }
}
```

**When It's Used:**
- **Local Development**: can auto-load `OPENAI_API_KEY` from `../../backend/.env`
- **Smart Loading**: won't override existing environment variables
- **GitHub Actions**: not used for secrets; workflows should use repository secrets, repository
  variables, or explicitly masked ad hoc workflow inputs

**Local Development:**
```bash
# No need to export OPENAI_API_KEY - auto-loaded from backend/.env
./run-relationship-query-realapi-tests.sh "openai:onnx:lucene"
```

**GitHub Actions:**
```yaml
env:
  OPENAI_API_KEY: ${{ github.event.inputs.openai_api_key || secrets.OPENAI_API_KEY }}
  PINECONE_API_HOST: ${{ github.event.inputs.pinecone_host || vars.PINECONE_API_HOST }}
  PINECONE_INDEX_NAME: ${{ github.event.inputs.pinecone_index_name || vars.PINECONE_INDEX_NAME }}
  PINECONE_ENVIRONMENT: ${{ github.event.inputs.pinecone_environment || vars.PINECONE_ENVIRONMENT }}
```

---

## CI/CD Integration

### GitHub Actions: integration-tests-manual.yml

The manual workflow includes a step to run RealAPI tests:

```yaml
- name: Run Relationship Query Integration Tests
  run: |
    cd ai-infrastructure-module/integration-Testing/relationship-query-integration-tests
    bash run-relationship-query-realapi-tests.sh
  env:
    OPENAI_API_KEY: ${{ github.event.inputs.openai_api_key || secrets.OPENAI_API_KEY }}
```

### GitHub Actions: parent-verify.yml

The automatic workflow runs standard verify (excludes RealAPI tests):

```yaml
- name: Run Maven Verify on AI Infrastructure Module
  run: |
    cd ai-infrastructure-module
    mvn clean verify -B -V
```
✅ Includes: Non-RealAPI tests
❌ Excludes: RealAPI tests

---

## Test Execution Flow

### Maven Standard Build (`mvn clean verify`)
```
┌─────────────────────────────────────────────┐
│ ai-infrastructure-module                    │
└─────────────────────────────────────────────┘
        ↓
        ├─→ ai-fabric-core
        │   ├─ Unit Tests: ✅
        │   ├─ Integration Tests: ✅
        │   └─ RealAPI Tests: ❌ (excluded)
        │
        ├─→ integration-tests
        │   ├─ Integration Tests: ✅
        │   └─ RealAPI Tests: ❌ (excluded)
        │
        └─→ relationship-query-integration-tests
            ├─ RelationshipQueryBasicIntegrationTest: ✅
            └─ RealAPI Tests: ❌ (excluded)
```

### RealAPI Execution (Manual)
```
bash run-relationship-query-realapi-tests.sh (with OPENAI_API_KEY)
        ↓
mvn failsafe:integration-test failsafe:verify -P realapi
        ↓
    Runs:
    ├─ LawFirmRealApiIntegrationTest
    ├─ FinancialFraudRealApiIntegrationTest
    └─ ECommerceRealApiIntegrationTest
```

---

## Summary

| Aspect | Details |
|--------|---------|
| **Standard Verify** | `mvn clean verify` |
| **Runs in Verify** | RelationshipQueryBasicIntegrationTest |
| **Excluded from Verify** | All *RealApiIntegrationTest.java |
| **RealAPI Script** | `run-relationship-query-realapi-tests.sh [LLM:EMBEDDING[:VECTOR_DB]]` |
| **API Key (Local)** | Auto-loaded from backend/.env |
| **API Key (CI/CD)** | Provided through GitHub Actions secrets, with masked workflow inputs available for ad hoc manual runs |
| **Provider Flexibility** | ✅ Supports multiple LLM, embedding, and vector DB providers |
| **Default Providers** | openai:onnx:lucene |
| **Configuration** | Environment variables or script arguments |
| **CI/CD Workflows** | Both manual and automatic configured |

---

## Benefits

✅ **Fast standard builds** - No API keys needed
✅ **Comprehensive testing** - All functionality covered
✅ **Clear separation** - RealAPI tests explicitly excluded
✅ **Flexible providers** - Test with different LLM, embedding, and vector DB combinations
✅ **Auto-configuration** - API key loaded automatically from backend/.env
✅ **Easy maintenance** - Scripts and profiles handle everything
✅ **CI/CD ready** - Automated and manual workflows included
✅ **Cost efficient** - API calls only when intentionally triggered
