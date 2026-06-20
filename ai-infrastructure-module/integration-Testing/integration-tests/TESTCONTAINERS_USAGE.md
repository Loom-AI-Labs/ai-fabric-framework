# Testcontainers Usage Guide

## Overview

The Testcontainers integration allows you to use real vector database containers in your tests while defaulting to Lucene for fast unit tests.

Release-supported AI Fabric vector provider modules with container contract coverage are
Qdrant, Weaviate, and Milvus. The shared Testcontainers support package also contains generic
Chroma and pgvector container fixtures, but this repository does not currently ship AI Fabric
Chroma or pgvector vector provider modules.

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

Recommended usage:
1. Activate the `testcontainers` profile
2. Specify a module-backed vector database type

When the `TestcontainersInitializer` is registered, explicitly setting
`-Dai.vector-db.type=milvus`, `qdrant`, or `weaviate` can also enable early container startup
without adding the profile manually.

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

# Chroma and pgvector container fixtures exist for future provider work, but
# they do not activate an AI Fabric vector provider module in the current repo.
```

**Result:** Testcontainers starts the appropriate container, injects connection properties, and tests run against the containerized database.

If Docker is unavailable or the selected container cannot start, the initializer fails fast
before setting `testcontainers.enabled=true`. Use `lucene` or `memory` for no-Docker test runs.

### 3. Integration Tests

```bash
# Integration tests with Testcontainers
mvn verify \
  -Dtest=RealAPIIntegrationTest \
  -Dspring.profiles.active=real-api-test,testcontainers \
  -Dai.vector-db.type=milvus
```

## Supported Module-Backed Container Types

- `milvus` - Milvus vector database
- `qdrant` - Qdrant vector database
- `weaviate` - Weaviate vector database

## Generic Future-Provider Fixtures

- `chroma` - Chroma container wiring only; no AI Fabric vector provider module currently ships; not auto-enabled by `TestcontainersInitializer`
- `pgvector` - PostgreSQL with pgvector extension wiring only; no AI Fabric vector provider module currently ships; not auto-enabled by `TestcontainersInitializer`

## Non-Container Types (No Testcontainers)

These types do NOT use Testcontainers (even with the profile active):
- `lucene` - Apache Lucene (default, fast, no containers)
- `memory` - In-memory vector database

## How It Works

1. **TestcontainersInitializer** checks:
   - Is `ai.vector-db.type` set to a module-backed container type?
   - Is the `testcontainers` profile active, or was the type explicitly set through `-Dai.vector-db.type=...` / `VECTOR_DB_TYPE`?
   - If yes → starts the container early and sets `testcontainers.enabled=true`

2. **VectorDatabaseContainerAutoConfiguration** activates when:
   - `testcontainers.enabled=true`
   - AND `ai.vector-db.type` matches a container type
   - Chroma/pgvector fixtures require this explicit property because the initializer only auto-enables shipped module-backed providers.
   - For Milvus, Qdrant, and Weaviate, the auto-configuration reuses any container already started
     by `TestcontainersInitializer` instead of starting a second container.

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
