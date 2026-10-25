# Development Guidance

Read `docs/Framework-Dev-Guides/LLM-guides/AI_FABRIC_FRAMEWORK_PHILOSOPHY.md`
before changing AI validation, prompts, routing, retrieval, or actions.
Use `docs/llm-context/AI_FABRIC_CONTEXT_INDEX.md` to locate related contracts.

Apply "Strict Trust Boundaries, Minimal AI Contracts": hard authority and
execution-safety boundaries, minimal necessary structured fields, guided LLM
decisions and visible uncertainty. Never hide decisions with keyword/regex
matching or unapproved recovery. READ actions need not use vector spaces.
Every new hard validator must name the boundary or execution requirement it
protects; semantic confidence alone is not automatically a rejection gate.
