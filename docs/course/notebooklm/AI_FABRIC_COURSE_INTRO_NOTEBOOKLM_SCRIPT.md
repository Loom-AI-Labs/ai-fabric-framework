# NotebookLM Single-Source Production Script: AI Fabric Course Introduction

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general AI knowledge,
external sources, or assumptions about AI Fabric.

Create a concise two-to-three-minute Explainer video titled **Build Production-Oriented AI Workflows
With Java And Spring Boot**. Follow the six scenes in order. Use each **Visual** block as production
direction and each **Narration** block as the spoken message. Keep AI Fabric and the developer's
learning journey central throughout the video.

Do not turn this into a detailed framework architecture lesson. CORE-01 provides that explanation.
Do not turn it into a semantic-search tutorial. The Quickstart demonstrates that capability. Do not
claim that planned lessons, starter checkpoints, certificates, or videos are already published.

## Production Direction

- Audience: Java and Spring Boot developers evaluating or starting the AI Fabric course.
- Voice: welcoming, confident, practical, and technically precise. Address the developer as **you**.
- Target duration: two to three minutes.
- Format: course orientation, not a code-along and not a framework deep dive.
- Visual style: clean light backgrounds, navy and teal accents, readable Java and Spring Boot labels,
  simple request-flow diagrams, and restrained motion.
- Avoid humanoid robots, futuristic scenery, decorative AI imagery, invented product interfaces,
  and long paragraphs of on-screen text.

## Scene 1: Begin With The Spring Boot Application You Know

**Visual:** Show a conventional Spring Boot application with controllers, services, repositories,
authorization, and a database. Add a new requirement labeled "AI-enabled workflow."

**Narration:**

Welcome to **Build Production-Oriented AI Workflows With Java And Spring Boot**.

This course is for developers who already understand ordinary application development and now need
to add useful AI capabilities without losing control of business rules, security, data, or testing.

You will begin with the Spring Boot application shape you already know. Then you will add AI one
bounded capability at a time.

## Scene 2: Introduce AI Fabric Briefly

**Visual:** Place AI Fabric between the Spring Boot application and labeled AI systems: Model,
Embedding Provider, and Vector Store.

**Narration:**

AI Fabric adds application-level AI capabilities to Spring Boot, including retrieval, governed
actions, memory, privacy, and provider orchestration. This Quickstart demonstrates one small
capability before the Core track explains the complete architecture.

AI Fabric does not replace your domain services or repositories. It helps your application
coordinate model intelligence, retrievable evidence, controlled operations, and provider choices
through explicit Java and Spring Boot boundaries.

## Scene 3: Reach A Useful Result First

**Visual:** Show five support articles, an indexing operation, and a paraphrased support question
returning one article with an evidence ID and score.

**Narration:**

The Quickstart is deliberately practical. You take application-owned support articles, index their
approved content, and retrieve the expected article with different wording.

The first result is evidence, not a polished chatbot answer. You can inspect the source identity,
content, metadata, and similarity score. You also see the honest empty result before indexing.

The local path uses ONNX embeddings and Lucene vector storage, so the workflow does not require an
OpenAI key, Docker, or a framework source checkout.

## Scene 4: Build One Continuing Application

**Visual:** Grow one Support Knowledge Assistant through a sequence of labeled capabilities.

```text
Semantic search
  -> evidence-grounded RAG
  -> governed actions and confirmation
  -> backend conversation memory
  -> tenant security and privacy
  -> release-ready tests
```

**Narration:**

The Core track continues with the same application rather than restarting in a new domain for every
lesson.

You model and index application data, generate answers from approved evidence, register typed
actions, confirm writes before execution, preserve conversation state in the backend, enforce tenant
and privacy boundaries, and test the complete workflow.

CORE-01 explains what AI Fabric is, why it exists, when to use it, how its modules fit together, and
which responsibilities must remain in your application.

## Scene 5: Move From A Feature To Production Evidence

**Visual:** Show the course path from Quickstart to Core, Production, Case Studies, Coding Assistant,
and Capstone.

**Narration:**

After the Core track, the Production track covers provider profiles, indexing and backfill, RAG
quality, managed vector storage, and release operations.

Real-application case studies examine shopping, account resolution, behavior analysis, tenant
isolation, and privacy. They include failures as well as successful flows, because a production claim
needs evidence that incorrect states remain visible.

The coding-assistant track shows how to give an assistant release-pinned framework context, bound its
changes, require tests, and independently review the result. The capstone brings those capabilities
together in an application you design.

## Scene 6: Understand How You Will Learn

**Visual:** Split the screen into Build Manually and Use A Coding Assistant. Rejoin both paths at the
same tests, evidence, and knowledge check.

**Narration:**

Each published implementation lesson supports two paths. You can build manually, or you can use the
lesson's bounded coding-assistant prompt. Both paths must reach the same behavior, tests, intentional
failure, and completion evidence.

The course does not reward copied code or fluent AI wording. It asks you to identify ownership,
trace the request flow, diagnose visible failures, and prove what the application actually did.

Start with the Quickstart. Produce one trustworthy result. Then enter the Core track and learn the
architecture that lets that result grow into a real application workflow.

## Accuracy Guardrails For NotebookLM

- Keep AI Fabric and the course journey central in every scene.
- Do not present the course as a generic AI, Spring AI, RAG, or prompt-engineering course.
- Do not claim that AI Fabric replaces application services, repositories, authorization, or domain
  policy.
- Do not imply that an LLM is required for the Quickstart.
- Do not describe generated wording as proof that retrieval or an action succeeded.
- Do not claim every lesson or checkpoint is already published.
- Do not introduce performance, accuracy, compliance, adoption, certification, or uptime claims.
- Do not invent framework annotations, APIs, modules, providers, or course requirements.
- Keep the course introduction concise; leave the complete framework architecture to CORE-01.
