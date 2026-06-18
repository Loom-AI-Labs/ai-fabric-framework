# GitHub Actions Integration (Chat Session)

This module is designed to be invoked from the manual integration workflow and provider matrix suites.

## Runner

- `run-chat-session-realapi-tests.sh` accepts: `LLM:EMBEDDING:VECTOR_DB`

Example:

```bash
bash run-chat-session-realapi-tests.sh "openai:onnx:lucene"
```
