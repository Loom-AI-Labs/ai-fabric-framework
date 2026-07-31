# AI Fabric — User Guides

Welcome to **AI Fabric**, an open-source Java/Spring Boot framework for building AI-enabled
applications: provider integrations, embeddings, vector search/RAG, natural-language → query
translation, action execution, PII redaction, indexing, and reusable runtime primitives — all wired
through Spring Boot auto-configuration.

These guides are written for **external users** adopting the framework in their own applications.
Read them in order, or jump to what you need.

## Recommended Start

For new integrations, use the canonical [Getting Started](../getting-started/README.md) path first.
It is shorter, task-oriented, and includes an [LLM Start Here](../getting-started/00-llm-start-here.md)
page for coding assistant sessions. Keep this `docs/guides` section as the deeper legacy guide set.

If you are building training material for external users, start from
[Build AI-Enabled Applications with Java and Spring Boot](../course/AI_FABRIC_EXTERNAL_USER_COURSE.md).

| # | Guide | What you'll get |
|---|-------|-----------------|
| 1 | [Installation & Setup](01-installation.md) | Requirements, the BOM, your first dependency set, local ONNX assets. |
| 2 | [Understanding AI Fabric](02-understanding-ai-fabric.md) | The mental model: providers, embeddings, vector stores, orchestration, and how auto-configuration wires it together. |
| 3 | [Modules Reference](03-modules.md) | Every module, what it does, and when to add it. |
| 4 | [Configuration Reference](04-configuration.md) | The `ai.*` configuration properties: providers, vector stores, feature toggles. |
| 5 | [Use Cases](05-use-cases.md) | The problems AI Fabric solves, mapped to modules and config. |
| 6 | [Example Applications](06-example-apps.md) | A tour of the bundled real-app examples — what each demonstrates and how to run it. |
| 7 | [Quickstart: Build Your First App](07-quickstart.md) | A hands-on, end-to-end first application. |

## At a glance

- **Coordinates:** group `io.github.loom-ai-labs`, BOM `ai-fabric-bom`, modules `ai-fabric-*`.
- **Latest version:** `0.5.2`.
- **Java packages:** `ai.fabric.*`.
- **Requirements:** Java 21, Maven 3.9+, Spring Boot 4.1.x.
- **Entry point:** annotate a Spring Boot app with `@EnableAIInfrastructure` and add the starter.

## Try it locally (no keys required)

Every bundled example can boot fully offline with the `smoke` profile — no API keys, no external
services:

```bash
git clone https://github.com/loom-ai-labs/ai-fabric-framework.git
cd ai-fabric-framework
mvn -f ai-infrastructure-module/pom.xml -q install
mvn -f examples/real-apps/pom.xml -q install
mvn -pl smart-faq-assistant -f examples/real-apps/pom.xml \
    spring-boot:run -Dspring-boot.run.profiles=smoke
```

Then continue with [Quickstart](07-quickstart.md) to build your own.
