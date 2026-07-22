# Independent Review Prompt: PROD-07

Review the PROD-07 checkpoint findings-first.

Verify:

- application code still depends on AI Fabric contracts, not Qdrant SDK types;
- Lucene and Qdrant profiles select exactly one vector provider;
- ONNX dimensions match the Qdrant collection;
- collection prefix and payload schema are explicit;
- tenant filters and post-hit verification remain unchanged;
- both golden scorecards pass against Qdrant;
- create/update/delete/count and stable vector identity are exercised;
- typed diagnostics describe the effective REST/gRPC transport without exposing keys;
- unreachable selected Qdrant fails visibly with no Lucene fallback;
- Docker resources are always cleaned up;
- cloud claims are `NOT RUN` unless protected-key evidence exists;
- clean tests, packaged Lucene smoke, and Docker Qdrant smoke all pass.

Run the documented gates and cite file/line evidence for every finding. Do not approve from startup
health alone.
