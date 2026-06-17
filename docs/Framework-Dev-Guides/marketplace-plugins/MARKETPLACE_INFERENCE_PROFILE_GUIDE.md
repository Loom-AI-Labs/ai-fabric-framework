# Marketplace Inference Profile Guide

Status: current branch guide (2026-04-15)

This guide explains the shipped `INFERENCE_PROFILE` marketplace type.

Companion guides:

- `./MARKETPLACE_PLUGIN_AUTHOR_GUIDE.md`
- `./MARKETPLACE_PLUGIN_MANIFEST_REFERENCE.md`
- `./MARKETPLACE_PLUGIN_VERIFICATION_AND_TROUBLESHOOTING_GUIDE.md`
- `doc/Productization/future-work/MarketPlace/MARKETPLACE_INFERENCE_PROFILE_PRODUCTIZATION_PLAN.md`

---

## 1) What An Inference Profile Is

`INFERENCE_PROFILE` is the marketplace type used for bounded LLM and embedding offers.

It is not:

- a raw provider JSON patch
- a model file marketplace
- a runtime code plugin

It is a bounded marketplace package that compiles into deployment `providerConfig`.

---

## 2) Current Compile Target

Inference-profile installs compile into:

- deployment `providerConfig`
- marketplace-managed inference metadata inside `providerConfig.marketplaceInference`

The compiler writes only provider fields the runtime already understands.

This keeps inference profiles aligned with the existing deployment and runtime model.

---

## 3) Supported Use Cases

Current supported use cases:

- BYOK OpenAI-compatible profile
- managed endpoint-profile selection per purpose
- separate orchestration, generation, and embedding choices

Current first-party example:

- `mkp-inference-byok-openai`

---

## 4) Purpose-Specific Sections

Current inference manifest shape supports:

- `orchestration`
- `generation`
- `embedding`

At least one must be present.

Each section may carry:

- provider identity
- endpoint-profile reference
- direct base URL or install-field-bound base URL
- secret-ref binding
- model or install-field-bound model
- operational tuning fields such as max tokens, temperature, and timeout

Embedding sections may also include embedding-specific fields such as dimensions.

---

## 5) Endpoint Profiles And BYOK

There are two main patterns.

### 5.1 Managed endpoint profile

Use:

- `endpointProfileRef`

This is appropriate when the platform manages the endpoint identity and secret binding.

### 5.2 BYOK

Use install-form fields plus field references such as:

- `baseUrlField`
- `apiKeySecretRefField`
- `modelField`

This is appropriate when the customer supplies their own provider endpoint or secret ref.

Good example:

- `mkp-inference-byok-openai`

---

## 6) Important Platform Rules

### 6.1 One enabled inference profile per deployment

The install service enforces:

- only one enabled `INFERENCE_PROFILE` plugin may exist per deployment

If you need a different provider package:

- disable or remove the old inference profile first

### 6.2 Publish and apply still required

Installing or updating an inference profile only changes the draft.

Live provider behavior changes only after:

- draft validation
- publish
- apply

### 6.3 Secrets stay outside the manifest

Use:

- install `secretRefs`

Do not embed:

- provider API keys
- bearer tokens
- deployment secrets

---

## 7) Real First-Party Example

### `mkp-inference-byok-openai`

Use case:

- let a customer bring an OpenAI-compatible endpoint and key

Install form:

- `apiKey` as `secretRef`
- optional `baseUrl` as `url`
- optional `generationModel` as `text`
- optional `embeddingModel` as `text`

Contribution model:

- generation provider section
- embedding provider section

Published result:

- generation fields land in `providerConfig`
- embedding fields land in `providerConfig`
- marketplace-managed metadata lands in `providerConfig.marketplaceInference`

---

## 8) Verification Expectations

For every inference profile, verify:

1. install exists and entitlement state is correct
2. draft contains the expected provider changes
3. draft validation passes
4. provider connectivity checks are ready
5. published version contains the intended provider config
6. release verification confirms runtime inference contract

Good live proof:

- generation and embedding connectivity are both ready
- published provider config contains the expected base URL, model, and secret-ref bindings

---

## 9) Common Failure Modes

### 9.1 Missing secret ref

Cause:

- required secret ref was omitted at install time

Fix:

- update install `secretRefs`
- resolve the install
- validate again

### 9.2 Invalid endpoint profile reference

Cause:

- `endpointProfileRef` does not exist or is not active

Fix:

- use a valid active endpoint profile

### 9.3 Provider mismatch

Cause:

- endpoint profile provider type conflicts with the provider declared in the inference section

Fix:

- align the endpoint profile to the intended provider type

### 9.4 Multiple enabled inference profiles

Cause:

- deployment already has another enabled inference-profile install

Fix:

- disable or remove the existing inference profile before enabling another

---

## 10) Recommended Authoring Pattern

Prefer:

1. start with one purpose section
2. bind install-form values through field references
3. verify provider connectivity
4. then add orchestration/generation split if needed

This keeps authoring and troubleshooting smaller than trying to ship a large multi-provider profile first.
