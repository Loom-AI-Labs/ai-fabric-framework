# Coding Assistant Prompt: PROD-06

Implement the RAG quality checkpoint in the continuing Support Knowledge Assistant.

Constraints:

- start from `course-0.4.0-p05-live-data-sync`;
- verify that the starter tag exists before editing; if it is missing, stop and report that the 0.4
  checkpoint is unpublished, and never substitute `main` or an older 0.3 tag;
- keep AI Fabric `0.4.0`, Java 21, Spring Boot 4.1.x, ONNX, and Lucene;
- derive tenant identity from the authenticated backend principal;
- add tenant-aware golden questions with expected and forbidden evidence IDs;
- support required current fragments, forbidden stale fragments, and explicit no-evidence cases;
- return structured failure codes; do not grade exact generated prose;
- expose prompt resolution version and required-slot flags without exposing prompt bodies;
- retain visible retrieval/generation failures and disabled provider behavior;
- add focused tests, full clean verification, and packaged-JAR HTTP proof;
- document OpenAI only as an optional runtime-secret observation;
- do not add fallback text, fake generation, text-matching intent logic, or secrets.

Before editing, identify the source database, vector evidence service, tenant boundary, prompt
resolver, packaged smoke, and current test fixtures. After editing, report exact test counts and
whether optional OpenAI work ran.
