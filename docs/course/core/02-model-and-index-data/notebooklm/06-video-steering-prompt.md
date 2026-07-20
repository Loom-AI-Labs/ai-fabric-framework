# NotebookLM Video Steering Prompt

```text
Create a concise 6-8 minute technical explainer for Java and Spring Boot developers.
Title: From Application Record To Semantic Evidence.

Use `07-video-script.md` as the primary narration and scene-order source. Use the rest of the supplied
AI Fabric 0.3.3 CORE-02 source pack only to verify terminology and add supporting visuals. This is an
architecture explainer, not a code-along and not proof that the future checkpoint was executed.

Speak directly to the Java developer using "you". Do not describe the audience as "the learner".

Explain:
- semantic similarity versus keyword matching and general model knowledge;
- domain record versus approved search projection versus vector evidence;
- `@AICapable`, `@AISearchable`, `@AIContext`, and configuration precedence;
- application record -> approved content/metadata -> ONNX embedding -> Lucene vector;
- compatible embedding models and dimensions for content and query vectors;
- query -> query embedding -> similarity search -> evidence response;
- similarity score as a ranking signal rather than a probability;
- application, AI Fabric, provider, vector store, and browser ownership;
- honest no-evidence behavior before indexing;
- semantic retrieval versus RAG generation;
- what the practical lesson must prove before publication.

Trace one request end to end. Show the failure path where database rows exist but vectors do not.
Clearly distinguish deterministic retrieval from language-model generation.

Do not invent APIs, properties, endpoints, commands, performance figures, compliance claims, or
expected model wording. Do not imply the video or executable lab is already published. End with this
question: you seeded five support articles but have not indexed them; what should semantic search
return, and why?
```
