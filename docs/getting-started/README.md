# AI Fabric Getting Started

This directory is the canonical, versioned Getting Started path for AI Fabric.
It is written for two readers:

- A developer integrating AI Fabric into a Spring Boot application.
- An LLM coding assistant that needs a small, reliable context pack before editing code.

Current assumptions:

- AI Fabric version: `0.3.3`
- Java: `21`
- Spring Boot: `4.1.x`
- Maven group: `io.github.loom-ai-labs`

## Fast Path

1. Read [LLM Start Here](00-llm-start-here.md) if an AI coding assistant is helping.
2. Read [Choose Your Path](01-choose-your-path.md) to pick the smallest module set.
3. Add dependencies from [Installation](02-installation.md).
4. Build one vertical slice:
   - [First Semantic Search](03-first-semantic-search.md)
   - [First RAG Chat](04-first-rag-chat.md)
   - [First Governed Action](05-first-governed-action.md)
5. Add production guardrails from [Production Checklist](13-production-checklist.md).

For the public learner curriculum, use
[Build Production-Oriented AI Workflows with Java and Spring Boot](../course/AI_FABRIC_EXTERNAL_USER_COURSE.md).

## Docs In This Section

| Doc | Use it when |
| --- | --- |
| [00 LLM Start Here](00-llm-start-here.md) | You want a coding assistant to make framework-safe changes. |
| [01 Choose Your Path](01-choose-your-path.md) | You need to decide which AI Fabric modules to install. |
| [02 Installation](02-installation.md) | You need Maven dependencies and baseline config. |
| [03 First Semantic Search](03-first-semantic-search.md) | You want to index and retrieve your own data. |
| [04 First RAG Chat](04-first-rag-chat.md) | You want answers grounded in retrieved evidence. |
| [05 First Governed Action](05-first-governed-action.md) | You want the AI to propose and execute app actions safely. |
| [06 Chat Session Memory](06-chat-session-memory.md) | You need follow-up turns, pending confirmations, and pinned targets. |
| [07 Real Provider OpenAI](07-real-provider-openai.md) | You want real LLM and embedding calls through Spring AI. |
| [08 Local ONNX Embeddings](08-local-onnx-embeddings.md) | You want local embeddings without remote embedding calls. |
| [09 Vector Storage Lucene](09-vector-storage-lucene.md) | You want local durable vector storage. |
| [10 Security Access Policy](10-security-access-policy.md) | You need tenant/user-safe retrieval and action access. |
| [11 Testing and Verification](11-testing-and-verification.md) | You need CI and local verification commands. |
| [12 Real Apps Map](12-real-apps-map.md) | You want copyable examples by capability. |
| [13 Production Checklist](13-production-checklist.md) | You are preparing a release or deployment. |

## Source Ownership

These files are source of truth. The public `aifabric` website should render or mirror this content
and link back here rather than maintaining a separate implementation guide.
