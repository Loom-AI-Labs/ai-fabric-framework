# Runtime Transient Provider File URL Inputs Guide

Status: implemented in runtime/core/provider modules on 2026-05-27
Primary use case: owner-approved temporary document analysis from an external product
Related plan: `doc/Productization/future-work/MarketPlace/Products/Strategy/RoadMaps/Implementation/010_11_RUNTIME_TRANSIENT_PROVIDER_FILE_URL_INPUTS_PLAN.md`

## Purpose

LoomAI runtime supports short-lived HTTPS file URLs as transient provider inputs. This is for cases where an external product already owns private files and wants LoomAI to analyze those files without copying the file content into prompts, vector indexes, logs, or chat history.

The external product sends selected documents in `context.documents[]` with `temporaryAccessUrl`. Runtime converts those entries into provider-native `FILE_URL` input parts and requires explicit `documentUsage` evidence in the model/provider response.

## Runtime Endpoints

Both chat endpoints accept the same transient file URL contract:

- `POST /api/chat/me/query`
- `POST /api/chat/me/query-once`

Use `/query-once` for one-time analysis flows such as project creation. It treats `conversationId` as correlation only and does not create conversation history.

Use `/query` only when the UX intentionally needs a persistent conversation. Even then, temporary URLs and file bytes are not persisted.

## Request Shape

Transient documents live inside the canonical request `context.documents[]` array:

```json
{
  "query": "Analyze these owner-approved documents and prepare a structured answer.",
  "conversationId": "opaque-correlation-id",
  "mode": "support_assistant",
  "position": "document_analysis",
  "context": {
    "contextVersion": "external-document-analysis-v1",
    "documentSharingPolicy": {
      "scope": "single-request-analysis-only",
      "indexing": "not-allowed",
      "retention": "do-not-store-document-content",
      "access": "temporary-url-selected-by-owner",
      "ttl": "minutes"
    },
    "documents": [
      {
        "documentId": "f0d47c2b-00ef-4068-9bb2-c4423a5a76d3",
        "fileName": "owner-brief.pdf",
        "contentType": "application/pdf",
        "sizeBytes": 123456,
        "temporaryAccessUrl": "https://files.example.com/api/temporary-ai-access/<token>",
        "expiresAt": "2026-05-27T16:45:00Z",
        "providerInputHint": "typed-file-url"
      }
    ],
    "outputContract": {
      "format": "strict-json-object",
      "fields": ["documentUsage"]
    }
  }
}
```

Accepted document id aliases are `documentId` or `id`. Accepted filename aliases are `fileName`, `name`, or `title`. Accepted content type aliases are `contentType` or `mimeType`. Accepted expiry aliases are `expiresAt` or `temporaryAccessExpiresAt`.

## Runtime Validation

Runtime accepts at most 8 transient file URL inputs per request. Each declared `sizeBytes`, when present, must be non-negative and no larger than 50 MB. Each `expiresAt`, when present, must be an ISO-8601 instant, not expired, and no more than 24 hours in the future.

Every URL must:

- use HTTPS,
- include a valid host,
- not include user info,
- not point to `localhost`, `.localhost`, `.local`, metadata-service hosts, private IPv4 ranges, loopback ranges, link-local ranges, or private IPv6 ranges,
- match `ai.fabric.runtime.transient-file-url.allowed-hosts` when that deployment property is configured.

`ai.fabric.runtime.transient-file-url.allowed-hosts` is a comma-separated host allowlist. Exact hosts and wildcard subdomains are supported, for example:

```properties
ai.fabric.runtime.transient-file-url.allowed-hosts=files.example.com,*.customer-files.example.com
```

## Persistence And Redaction

Temporary file URLs are treated as bearer-style secrets even when they expire quickly.

Runtime and provider code must not:

- persist the URL in chat history,
- persist fetched file bytes,
- index the file or extracted content,
- log the raw URL,
- expose the raw URL in debug payloads,
- put raw document content into the normal prompt text.

Runtime redacts transient URL fields such as `temporaryAccessUrl`, `temporaryFileUrl`, and `fileUrl` to `[REDACTED_TRANSIENT_FILE_URL]` before request context can be stored or surfaced.

Provider fallback is disabled when transient file URLs are present. A second provider must not silently receive a request after the selected provider failed to process private file inputs.

## Provider Support Matrix

| Provider module | Current behavior |
|---|---|
| OpenAI | Uses Responses input parts. PDFs, text-like files, and Office-style documents use `input_file.file_url`; supported images use `input_image.image_url`. Unsupported types fail closed. |
| Azure OpenAI | Uses Responses input parts. PDFs are fetched transiently and sent as base64 `file_data`; supported images use native image URL inputs. Other types fail closed. |
| Anthropic | Uses native URL blocks for PDFs and supported images only. Text, Office, audio, video, and other types fail closed unless Anthropic support is added later. |
| Gemini | Fetches approved HTTPS URLs transiently and sends supported text, PDF, image, audio, and video bytes as `inlineData`. Unsupported types fail closed. |
| Cohere | Not active in the AI Fabric 0.3.x Spring AI execution path. Cohere configuration remains modeled for compatibility only. |
| ONNX starter | Not a chat/document analysis provider. Fail closed if selected for generation with transient file URLs. |

Text-like means `text/*`, JSON, XML, XHTML, YAML, CSV, and Markdown MIME types. Supported image types are JPEG, PNG, WebP, and GIF. OpenAI Office-style support includes Word, Excel, and PowerPoint MIME types.

Fail-closed behavior returns `documentUsage.status=NOT_USED` with a provider/type reason. It must not pretend the file was analyzed.

## Response Contract

When transient file URLs are supplied, the model/provider response must include `documentUsage` either in response metadata or in the JSON answer content.

Each selected document needs one usage item:

```json
{
  "documentId": "f0d47c2b-00ef-4068-9bb2-c4423a5a76d3",
  "fileName": "owner-brief.pdf",
  "contentType": "application/pdf",
  "status": "USED",
  "accessMethod": "TEMPORARY_URL",
  "evidence": ["Owner-safe fact extracted from the file"],
  "reason": "Temporary URL opened and analyzed successfully.",
  "provider": "openai"
}
```

Rules:

- `status` is `USED` or `NOT_USED`.
- `accessMethod` is `TEMPORARY_URL` when the provider actually used the file; otherwise `NONE`.
- `evidence` must contain owner-safe extracted facts for `USED`.
- `reason` must explain unsupported content type, expired URL, fetch failure, parse failure, or irrelevance for `NOT_USED`.
- If the provider returns no `documentUsage` for a request that included transient inputs, `AIProviderManager` converts the response to a fail-closed unsupported result.

The calling product must not mark a file as used unless LoomAI returns `status=USED` with owner-safe evidence for that file.

## Implementation Map

Runtime extraction and validation:

- `ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java`

Core DTOs and policy:

- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/dto/AIGenerationInputPart.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/dto/AIGenerationInputType.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/dto/TransientInputPolicy.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/dto/AIGenerationRequest.java`

Provider contract enforcement:

- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/provider/AIProviderManager.java`
- `ai-infrastructure-module/ai-infrastructure-core/src/main/java/com/ai/infrastructure/provider/TransientInputSupport.java`

Provider adapters:

- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiChatProvider.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiPromptMapper.java`
- `ai-infrastructure-module/providers/ai-fabric-provider-spring-ai/src/main/java/ai/fabric/provider/springai/SpringAiModelResolver.java`

## Verification Commands

Focused runtime extraction:

```bash
mvn -f ai-infrastructure-module/ai-fabric-runtime/pom.xml -q -Dtest=ChatRuntimeControllerPromptPreviewTest test
```

Core helper contract:

```bash
mvn -f ai-infrastructure-module/ai-infrastructure-core/pom.xml -q -Dtest=TransientInputSupportTest test
```

Provider-focused tests:

```bash
mvn -f ai-infrastructure-module/pom.xml -q -pl providers/ai-fabric-provider-spring-ai -am -Dtest='SpringAi*' -Dsurefire.failIfNoSpecifiedTests=false test
```

Cross-provider integration contract:

```bash
mvn -f ai-infrastructure-module/integration-Testing/integration-tests/pom.xml -q -Dtest=TransientFileUrlProviderContractIntegrationTest test
```

Broad affected-module regression:

```bash
mvn -f ai-infrastructure-module/pom.xml -q -pl ai-fabric-core,providers/ai-fabric-provider-spring-ai -am test
```

Whitespace check:

```bash
git diff --check
```

## External Product Integration Checklist

The calling product should:

- send documents only from a backend-mediated owner-authorized flow,
- send selected files in `context.documents[]` with `temporaryAccessUrl`,
- make URLs HTTPS, short-lived, direct byte responses, no redirects, no browser cookies, no custom headers,
- include `contentType`, `sizeBytes`, `expiresAt`, `fileName`, and `documentId`,
- use `/api/chat/me/query-once` for project creation analysis,
- keep raw document content out of the prompt,
- keep private files out of safe knowledge indexing,
- display `USED` only when `documentUsage` includes owner-safe evidence,
- display `NOT_USED` when provider support, URL access, parsing, or relevance fails,
- avoid sending storage URLs, Supabase JWTs, admin keys, repository tokens, or raw private logs.

The LoomAI deployment should:

- configure `ai.fabric.runtime.transient-file-url.allowed-hosts` for the external product file-access host,
- choose a provider that supports the expected file types for the target flow,
- keep `/query-once` as the default for one-time document analysis,
- run the verification commands above after provider or runtime changes.
