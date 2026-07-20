# Concepts And Request Flow

## Core Concepts

- **Domain record:** Application-owned support knowledge and its lifecycle.
- **Content projection:** Approved text selected for embedding and retrieval.
- **Embedding:** Numeric representation produced locally by the configured ONNX model.
- **Vector record:** Embedding plus stable identity, content, and metadata stored by Lucene.
- **Similarity search:** Retrieval of vectors near the query vector.
- **Evidence:** Returned record the application may present or later provide to RAG.

## End-To-End Trace

```text
1. Application saves KnowledgeArticle A-100.
2. Application/AI entity configuration selects title + body and category metadata.
3. AI Fabric calls the configured embedding contract.
4. ONNX produces a vector locally.
5. AI Fabric stores A-100, content, vector, and metadata through Lucene.
6. User asks a differently worded support question.
7. ONNX embeds the query with the compatible model.
8. Lucene returns similar vectors.
9. AI Fabric returns evidence IDs, scores, content, and metadata.
10. Application projects the result to its API/UI.
```

## Ownership

| Owner | Responsibility |
| --- | --- |
| Application | Article lifecycle, approved content, stable ID, metadata, endpoint response |
| AI Fabric | Entity, embedding, vector, and search contracts |
| ONNX provider | Local embedding inference |
| Lucene provider | Vector persistence and similarity search |
| Browser | Request and presentation only |

## Failure Path

If step 3-5 never ran, the database record exists but similarity search has no vector to return. The
correct response is no evidence plus readiness diagnostics. The correction is indexing, not a canned
answer or browser keyword rule.

