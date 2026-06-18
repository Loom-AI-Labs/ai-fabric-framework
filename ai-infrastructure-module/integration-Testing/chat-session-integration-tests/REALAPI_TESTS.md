# Chat Session RealAPI Tests

This module contains RealAPI integration tests for the chat-session feature (conversation history enrichment + turn recording).

## Local Run

From the module directory:

```bash
./run-chat-session-realapi-tests.sh "openai:onnx:lucene"
```

Or from repo root:

```bash
cd ai-infrastructure-module/integration-Testing/chat-session-integration-tests
./run-chat-session-realapi-tests.sh "openai:onnx:lucene"
```

## Credentials

Provide provider credentials via environment variables (examples):

- `OPENAI_API_KEY` (OpenAI)
- `ANTHROPIC_API_KEY` (Anthropic)
- `GEMINI_API_KEY` (Gemini)
- `COHERE_API_KEY` (Cohere)
- `AZURE_API_KEY` + `AZURE_ENDPOINT` (Azure)
