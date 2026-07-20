# QS-01 NotebookLM Lesson Brief

## Purpose

Create a 6-8 minute pre-lesson architecture explanation titled **From Application Record To Semantic
Evidence** for Java and Spring Boot developers who have not used AI Fabric.

This is theory orientation, not a code-along. Address the developer directly as **you**. By the end,
the developer should be able to explain why a database row is not vector evidence and trace one
semantic-search request end to end.

## Required Topics

1. Semantic similarity versus keyword matching and general model knowledge.
2. Domain record versus approved search projection versus vector evidence.
3. `@AICapable`, `@AISearchable`, `@AIContext`, and configuration precedence.
4. Application record -> approved content/metadata -> ONNX embedding -> Lucene vector.
5. Compatible embedding models and dimensions for indexed content and queries.
6. Query -> query embedding -> top-K similarity search -> evidence response.
7. Similarity score as a ranking signal, not a probability or guaranteed confidence.
8. Application, AI Fabric, provider, vector store, and browser ownership.
9. Honest no-evidence behavior before indexing.
10. Semantic retrieval versus the later LLM generation step in RAG.
11. What the executable lab must prove before publication.

## Prohibited Claims

- Do not describe this preview as an executed lab.
- Do not claim the NotebookLM video is already published.
- Do not invent performance, accuracy, compliance, or production-readiness numbers.
- Do not describe database records as indexed evidence.
- Do not imply a language model is required for semantic retrieval.

## Lab Handoff

Use `07-video-script.md` as the primary narration and scene-order source. End by asking the developer
to predict the result of searching after seed but before index, then hand off to the lesson and
knowledge check.
