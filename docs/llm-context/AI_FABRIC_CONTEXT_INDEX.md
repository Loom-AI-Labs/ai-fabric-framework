# AI Fabric Context Index

Use this index to decide which files a coding assistant should read.

| User asks for | Read first | Then read |
| --- | --- | --- |
| "What can AI Fabric unlock in this app?" | `docs/llm-context/AI_FABRIC_OPPORTUNITY_SCANNER.md` | `docs/llm-context/AI_FABRIC_CAPABILITY_MAP.md`, `docs/getting-started/12-real-apps-map.md` |
| "Find AI opportunities in my Java app" | `docs/llm-context/AI_FABRIC_OPPORTUNITY_SCANNER.md` | `docs/getting-started/01-choose-your-path.md`, `docs/llm-context/AI_FABRIC_COMMON_TASK_RECIPES.md` |
| "Add AI Fabric to my app" | `docs/getting-started/02-installation.md` | `docs/getting-started/01-choose-your-path.md` |
| "Semantic search" | `docs/getting-started/03-first-semantic-search.md` | `docs/getting-started/09-vector-storage-lucene.md` |
| "RAG chat" | `docs/getting-started/04-first-rag-chat.md` | `docs/Framework-Dev-Guides/retrieval-vectorization/RAG_EMBEDDING_QUERY_COMPOSITION_GUIDE.md` |
| "Actions" | `docs/getting-started/05-first-governed-action.md` | `docs/Framework-Dev-Guides/actions-governance/ACTIONS_AND_CONFIRMATION_INTERCEPTORS_GUIDE.md` |
| "Chat history" | `docs/getting-started/06-chat-session-memory.md` | `ai-infrastructure-module/ai-fabric-chat-session/src/main/java` |
| "OpenAI" | `docs/getting-started/07-real-provider-openai.md` | `docs/Framework-Dev-Guides/runtime-integration/SPRING_AI_PROVIDER_INTEGRATION_GUIDE.md` |
| "ONNX" | `docs/getting-started/08-local-onnx-embeddings.md` | `docs/guides/01-installation.md` |
| "Tenant safety" | `docs/getting-started/10-security-access-policy.md` | `docs/Framework-Dev-Guides/security-auth/RUNTIME_AUTHORIZATION_AND_ACCESS_CONTROL_GUIDE.md` |
| "Behavior signals" | `docs/getting-started/12-real-apps-map.md` | `examples/real-apps/behavior-churn-signals/README.md` |
| "Production release" | `docs/getting-started/13-production-checklist.md` | `docs/Framework-Dev-Guides/testing-verification/VERIFICATION_PLAYBOOK.md` |

Read the source code only after the relevant guide identifies the module boundary.
