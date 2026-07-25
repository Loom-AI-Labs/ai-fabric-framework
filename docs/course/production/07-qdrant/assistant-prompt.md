# Coding Assistant Prompt: PROD-07

Implement the managed-vector checkpoint in the continuing Support Knowledge Assistant.

Constraints:

- start from `course-0.4.0-p06-rag-quality`;
- verify that the starter tag exists before editing; if it is missing, stop and report that the 0.4
  checkpoint is unpublished, and never substitute `main` or an older 0.3 tag;
- add the published AI Fabric Qdrant module; do not call its native SDK from application code;
- retain Lucene for the normal local gate and select Qdrant through a separate profile;
- retain ONNX and prove 384-dimensional Qdrant configuration;
- use a pinned Docker Qdrant image and isolated collection prefix;
- expose a safe typed provider-readiness projection with no API key;
- rerun both tenant golden suites unchanged;
- prove payload schema, stable upsert identity, delete/count, and provider durability diagnostics;
- configure fallback false and prove an unreachable selected Qdrant returns a visible failure;
- add CI, cleanup, exact commands, and a separate optional Qdrant Cloud section;
- never commit a cloud key or claim cloud verification when it was not run.

Before editing, map the current vector interface, Lucene profile, ONNX dimensions, tenant filters,
Data Sync lifecycle, quality suite, and packaged smoke. Report exact local Docker evidence and any
optional cloud status after implementation.
