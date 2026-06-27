# Pipeline Steps Reference

The default Spring pipeline discovers all `PipelineStep` beans and executes them by ascending order.

| Order | Step Name | Class | Purpose |
| --- | --- | --- | --- |
| 10 | `SecurityAnalysis` | `SecurityAnalysisStep` | Blocks unsafe or invalid requests before deeper processing. |
| 20 | `AccessControl` | `AccessControlStep` | Resolves and enforces entity/runtime authorization. |
| 22 | `OrchestrationPolicyResolution` | `OrchestrationPolicyResolutionStep` | Resolves server-side mode/profile policy. |
| 23 | `AttachmentNormalization` | `AttachmentNormalizationStep` | Validates and bounds client-provided attachments and metadata. |
| 26 | `AttachmentPromptAugmentation` | `AttachmentPromptAugmentationStep` | Adds safe pinned-target context to the current LLM prompt. |
| 27 | `PendingActionPromptAugmentation` | `PendingActionPromptAugmentationStep` | Adds pending confirmation context for yes/no turns. |
| 50 | `IntentExtraction` | `IntentExtractionStep` | Converts user query plus context into `MultiIntentResponse`. |
| 52 | `TargetResolution` | `TargetResolutionStep` | Resolves action/RAG targets from attachments and request context. |
| 55 | `VectorSpaceResolution` | `VectorSpaceResolutionStep` | Resolves allowed vector spaces and target fan-out. |
| 60 | `IntentHandling` | `IntentHandlingStep` | Executes actions, confirmations, RAG, generation, or scope handling. |
| 65 | `OrchestrationResultNormalization` | `OrchestrationResultNormalizationStep` | Canonicalizes provider-dependent result shapes. |
| 70 | `MetadataBuilding` | `MetadataBuildingStep` | Adds safe metadata and optional debug snapshot evidence. |
| 80 | `SmartSuggestions` | `SmartSuggestionsStep` | Adds optional next-step suggestions. |
| 90 | `ResponseSanitization` | `ResponseSanitizationStep` | Produces safe response payload for clients. |
| 100 | `HistoryPersistence` | `HistoryPersistenceStep` | Records conversation turns when chat history is enabled. |

## Step Ordering Invariants

- Fail-closed security and access steps must run before intent extraction.
- Prompt augmentation must happen before intent extraction/generation.
- Target and vector-space resolution must happen after extraction and before intent handling.
- Result normalization must happen after intent handling and before metadata/sanitization/history.
- Response sanitization must happen before history persistence records final output.

## Adding A Step

When adding a new step:

- choose an order that preserves the invariants above;
- add a focused `*StepTest`;
- keep provider-specific behavior outside core pipeline logic;
- terminate early for security/business-rule failures instead of returning partial success.

## Verification

Run focused step tests after changing step order or behavior:

```bash
mvn -f ai-infrastructure-module/pom.xml -pl ai-fabric-core -Dtest='*StepTest' test
```
