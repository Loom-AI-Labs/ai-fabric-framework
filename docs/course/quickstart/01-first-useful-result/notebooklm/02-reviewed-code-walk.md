# Reviewed Code Shape

The video may show only these conceptual shapes. Exact compilable code belongs to the validated
learner checkpoint.

```java
@AICapable(entityType = "knowledge-article")
class KnowledgeArticle {
    @AISearchable
    private String title;

    @AISearchable
    private String body;

    @AIContext
    private String category;
}
```

Explain that annotations describe approved AI-facing behavior. They do not by themselves prove that
seed, update, delete, or backfill paths indexed anything.

```text
search(query) -> embedding contract -> vector search -> evidence response
```

Do not invent an endpoint, method signature, or property that is not present in the reviewed lesson
checkpoint when the video is generated.

