# Coding Assistant Prompt: Implement PROD-03 Prompt Overlays

Work from `course-0.4.0-p02-modes-positions` in the learner repository.

Before editing, verify that the tag exists. If it does not, stop and report that the 0.4 learner
checkpoint is not published; never substitute `main` or an older 0.3 tag.

Implement a complete `v1-course-support` RAG answer template while preserving the existing
classifier overlays and inherited framework action selector. Add safe diagnostics exposing only
candidate and resolved versions. Add tests for ordering, critical rules, required placeholders,
base fallback, packaged classpath resources, and absence of prompt bodies from diagnostics. Update
readiness, packaged smoke, HTTP requests, and checkpoint docs.

Do not edit framework defaults, duplicate unrelated prompts, assert exact hosted-model prose, expose
prompt content, or use prompt wording as authorization. Run `clean verify` and packaged smoke.
