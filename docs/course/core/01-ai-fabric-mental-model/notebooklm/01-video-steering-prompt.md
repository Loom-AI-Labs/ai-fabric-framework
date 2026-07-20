# NotebookLM Video Steering Prompt: What Is AI Fabric?

```text
Create a 10-12 minute technical Video Overview titled:
What Is AI Fabric? Why It Exists, When To Use It, And How It Works.

Use `00-video-script.md` as the primary narration and scene-order source. Use the remaining sources
only to verify architecture, module names, provider support, and terminology.

The audience is Java and Spring Boot developers. Speak directly to the developer using "you".

AI Fabric must remain the central subject. Explain:
- the application-level problem AI Fabric solves;
- what AI Fabric is and is not;
- why provider calls, retrieval, actions, memory, privacy, and policy need explicit boundaries;
- application ownership versus framework and provider ownership;
- the layered architecture and real module groups;
- one retrieval request flow and one confirmation-gated action flow;
- backend conversation memory, PII handling, and tenant-safe retrieval;
- when AI Fabric is a strong fit and when a direct Spring AI/provider call is simpler;
- how the Core and Production tracks build on the Quickstart.

Use clear architecture diagrams and module labels. Do not turn the video into a generic RAG,
embedding, agent, or Spring AI explanation. Do not invent APIs or claims. Do not imply every module is
required. End with the four architecture questions from the final script.
```
