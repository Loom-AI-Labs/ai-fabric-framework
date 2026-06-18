# Testcontainers Usage Guide

## Overview

The Testcontainers integration allows you to use real vector database containers in your tests while defaulting to Lucene for fast unit tests.

## Default Behavior

**Unit tests default to Lucene** - fast, no containers, no Docker required.

## Usage Patterns

### 1. Unit Tests (Default - Lucene)

```bash
# Simple unit tests - uses Lucene by default
mvn test

# Explicitly use Lucene (same as default)
mvn test -Dai.vector-db.type=lucene
```

**Result:** Tests run fast using Lucene vector database (no containers).

### 2. Unit Tests with Testcontainers

To use Testcontainers, you must:
1. Activate the `testcontainers` profile
2. Specify a container-supported vector database type

```bash
# Use Milvus container
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=milvus

# Use Qdrant container
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=qdrant

# Use Weaviate container
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=weaviate

# Use Chroma container
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=chroma

# Use pgvector container
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=pgvector
```

**Result:** Testcontainers starts the appropriate container, injects connection properties, and tests run against the containerized database.

### 3. Integration Tests

```bash
# Integration tests with Testcontainers
mvn verify \
  -Dtest=RealAPIIntegrationTest \
  -Dspring.profiles.active=real-api-test,testcontainers \
  -Dai.vector-db.type=milvus
```

## Supported Container Types

- `milvus` - Milvus vector database
- `qdrant` - Qdrant vector database
- `weaviate` - Weaviate vector database
- `chroma` - Chroma vector database
- `pgvector` - PostgreSQL with pgvector extension

## Non-Container Types (No Testcontainers)

These types do NOT use Testcontainers (even with the profile active):
- `lucene` - Apache Lucene (default, fast, no containers)
- `memory` - In-memory vector database

## How It Works

1. **TestcontainersInitializer** checks:
   - Is `testcontainers` profile active?
   - Is `ai.vector-db.type` set to a container type?
   - If both yes → sets `testcontainers.enabled=true`

2. **VectorDatabaseContainerAutoConfiguration** activates when:
   - `testcontainers.enabled=true`
   - AND `ai.vector-db.type` matches a container type

3. **Container starts** and injects properties into Spring environment

4. **Tests run** against the containerized database

## Examples

### Example 1: Fast Unit Tests (Default)
```bash
mvn test
# Uses: Lucene (fast, no containers)
```

### Example 2: Unit Tests with Milvus Container
```bash
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=milvus
# Uses: Milvus container (slower startup, real database)
```

### Example 3: Integration Tests with Qdrant
```bash
mvn verify \
  -Dtest=RealAPIIntegrationTest \
  -Dspring.profiles.active=real-api-test,testcontainers \
  -Dai.vector-db.type=qdrant
# Uses: Qdrant container
```

### Example 4: Force Lucene Even with Testcontainers Profile
```bash
mvn test \
  -Dspring.profiles.active=testcontainers \
  -Dai.vector-db.type=lucene
# Uses: Lucene (Testcontainers profile active but type is lucene, so no containers)
```

## Configuration Files

- `application-test.yml` - Default test config (defaults to `lucene`)
- `application-testcontainers.yml` - Testcontainers profile config (defaults to `lucene` if no type specified)

## Benefits

1. **Fast by default** - Unit tests use Lucene, no Docker required
2. **Flexible** - Override with Maven parameters to use containers
3. **No configuration needed** - Just add profile and type parameter
4. **Automatic cleanup** - Containers are stopped after tests

## Troubleshooting

### Containers not starting?

1. Check Docker is running: `docker ps`
2. Verify profile is active: `-Dspring.profiles.active=testcontainers`
3. Verify container type is specified: `-Dai.vector-db.type=milvus`
4. Check logs for container startup errors

### Want to use Lucene instead?

Just don't activate the `testcontainers` profile, or explicitly set `-Dai.vector-db.type=lucene`
