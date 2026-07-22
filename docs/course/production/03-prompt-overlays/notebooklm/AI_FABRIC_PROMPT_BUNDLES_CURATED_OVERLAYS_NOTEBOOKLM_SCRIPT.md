# NotebookLM Video Script: Prompt Bundles, Curated Packs, And Application Overlays

## Production Instruction

Produce a ten-minute technical video for Java developers. Explain AI Fabric prompt architecture from
this script only. Keep the application example central.

## Opening

Prompts in a production framework need two properties that are often in tension. Framework defaults
must improve centrally, while an application must own its domain language and special follow-up
behavior. Copying every prompt into every Spring Boot app destroys the first property. Editing prompts
inside a dependency destroys the second.

AI Fabric uses versioned classpath prompt bundles and ordered overlays.

## The Resource Key

Every prompt has a family, version, and name and is stored as:

```text
prompts/<family>/<version>/<name>.md
```

For the Support Knowledge Assistant, resolution tries `v1-course-support`, then `v1-support`, then
the framework base `v1`. The first existing complete template wins.

Emphasize that this is whole-template selection, not a Markdown patch. The application should add
only keys whose complete behavior it genuinely owns.

## Curated Packs

The curated support module supplies coherent support defaults: support-safe intent handling and
evidence-grounded answer behavior. The application overlay adds only its delta, including recent-
turn follow-up rules and a stricter answer projection. An untouched action-selection template still
comes from `v1`.

Describe this diagram:

```text
application JAR: v1-course-support
             |
curated support JAR: v1-support
             |
framework core JAR: v1
             |
PromptTemplateResolver -> one resolved template
```

## Prompt Versus Policy

Prompt rules guide probabilistic output. They may tell a model to use recent conversation, avoid
outside facts, or emit structured JSON. They cannot authorize a write, grant tenant access, validate
parameters, or consume confirmation. Those controls remain in modes, authenticated context, access
policy, typed action schemas, and execution services.

## Regression Proof

Do not assert exact generated prose. Assert deterministic properties: candidate order, resolved
version, required placeholders, critical safety rules, fallback for untouched keys, classpath
packaging, and absence of prompt bodies from public diagnostics.

An optional real-provider run can reveal model behavior changes, but it is observation rather than
the release contract.

## Incorrect Architecture

One incorrect design copies every framework prompt and edits one line. The app is then stranded on
old defaults. Another places security solely in prompt prose. A third exposes full prompts through a
health endpoint. All three confuse ownership or disclose unnecessary internals.

## Visible Failure

If an expected application template is renamed, resolution falls back to the curated support
version. The app still starts, but the regression test fails because the effective contract changed.
If no candidate exists, resolution fails clearly rather than inventing a template.

## Lab Bridge

In PROD-03 you will add the application answer template, test overlay-first and base-fallback
resolution, prove the resource is in the packaged JAR, and expose only safe version diagnostics.
The following lesson then applies the same ownership discipline to application data and vector
evidence during migration.
