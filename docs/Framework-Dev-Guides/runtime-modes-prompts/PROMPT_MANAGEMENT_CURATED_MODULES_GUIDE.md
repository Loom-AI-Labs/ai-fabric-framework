# Prompt Management And Curated Modules Guide

This guide explains the current prompt-management model in the platform after curated-module-driven prompt deployment was added.

It describes what works now for:

- curated module prompt baselines
- deployment-owned prompt drafts
- publish/apply prompt rollout
- Railway runtime prompt loading
- POC prompt preview and hot apply

It does not describe future roadmap behavior such as editing every low-level framework prompt template individually.

## 1. Short Answer

The platform now supports this end-to-end flow:

1. user creates a deployment from a template and a curated module
2. the curated module seeds the deployment prompt draft with the current 7 managed prompt fields
3. the user edits those prompts in the platform
4. the edited prompts are saved in the deployment draft in the platform database
5. publishing stores the prompt bundle in the immutable deployment version
6. applying generates a signed prompt artifact URL and passes it to runtime
7. runtime loads that prompt config on startup and uses it for normal chat execution

So prompt changes are no longer only a POC preview path. They now participate in normal publish/apply rollout.

## 2. Core Concepts

### 2.1 Deployment template

A deployment template still defines the technical baseline:

- LLM provider
- vector strategy
- runtime profile
- connector profile

This is infrastructure and behavior posture.

### 2.2 Curated module

A curated module defines the initial prompt and business-domain baseline for the deployment.

Current modules:

- `default`
- `commerce`

Each curated module carries:

- a prompt preset id
- an optional runtime curated pack id
- descriptive metadata for the platform UI

### 2.3 Managed prompt bundle

The platform currently manages these 7 prompt fields:

- `systemPrompt`
- `intentExtractionPrompt`
- `actionSelectionPrompt`
- `clarificationPrompt`
- `answerGenerationPrompt`
- `retrievalPrompt`
- `assistantUiPrompt`

These are the prompts visible on the `Prompts` page.

### 2.4 POC preview

POC preview remains a separate capability:

- it is session-scoped or request-scoped
- it can override prompts temporarily for preview
- it still requires admin authorization
- it does not replace publish/apply

## 3. What Happens On Deployment Creation

When a deployment is created:

1. the operator selects a deployment template
2. the operator selects a curated module
3. the platform creates a deployment draft
4. the draft provider config records:
   - `curatedModuleId`
   - `promptPresetId`
   - `curatedPackId` when the module has one
5. the draft prompt config is seeded from the curated module preset

That means the prompt editor no longer starts empty by default for curated deployments.

## 4. Where Prompt Data Is Stored

Prompt data is stored in the platform database as part of deployment lifecycle state.

### 4.1 Draft

The editable deployment draft stores prompt config in the platform DB.

This is the working state used by the `Prompts` page.

### 4.2 Published version

When the draft is published:

- the prompt config is copied into the immutable deployment version
- that version becomes the source for the rollout artifact

### 4.3 Prompt revisions

Prompt revision snapshots are also stored in DB.

These are operator-friendly named snapshots that can be restored back into the active draft.

### 4.4 Deployment artifact

When the deployment version is prepared for rollout, the platform also exposes a prompt artifact:

- `ai-prompt-config.json`

This is a generated release artifact served by the platform, not the primary editable source of truth.

The source of truth remains the deployment draft/version records in the database.

## 5. Current Operator Workflow

### 5.1 Create deployment

Use the `Deployments` page.

Choose:

- template
- curated module
- deployment name
- environment

The selected curated module seeds the first prompt baseline.

### 5.2 Review and edit prompts

Use the `Prompts` page.

You can:

- edit the 7 managed prompt fields
- save the prompt draft
- compare against the latest published baseline
- create named prompt revision snapshots
- restore prompt snapshots

### 5.3 Rebase to another curated module

The `Prompts` page now exposes curated module baseline cards.

When you apply a curated module baseline to the draft:

- the saved draft prompt bundle is replaced with that module preset
- the draft provider metadata is updated to the selected curated module
- the next publish/apply will use that new baseline

This is intentional reset behavior, not a merge.

Operators should use it when they want to switch the deployment-owned prompt baseline cleanly.

### 5.4 Publish

Publishing:

- stores the current prompt draft in the deployment version
- creates the immutable versioned prompt source
- creates the next editable draft from that same saved content

### 5.5 Apply

Applying:

- generates signed artifact URLs for actions, entities, routing, and prompts
- sends `AI_PROMPTS_DEPLOYMENT_CONFIG_FILE` to runtime
- sends `AI_CURATED_PACK` when the curated module maps to a runtime curated pack

### 5.6 Verify

After apply, the platform verifies that runtime actually loaded the prompt artifact.

There is now an explicit verification check:

- `runtime_prompt_config_matches_expected`

## 6. How Runtime Uses The Deployed Prompt Config

Runtime now loads the deployed prompt config file during startup.

Behavior:

- if `AI_PROMPTS_DEPLOYMENT_CONFIG_FILE` is present, runtime loads it
- only the 7 supported prompt keys are accepted
- the loaded values are applied as deployment prompt overlay metadata during chat execution

This means published/applied prompt config is part of normal runtime behavior.

## 7. How POC Prompt Preview Works Relative To Deployed Prompts

POC prompt preview still exists and is different from normal rollout.

Current precedence is:

1. deployed prompt config loaded from the applied artifact
2. request/session prompt preview overlay from POC

If both exist:

- POC preview wins for overlapping keys
- only for that preview request/session
- deployed config remains the live baseline

This gives operators a safe testing path without requiring a new release for every experiment.

## 8. Railway-Specific Behavior

For Railway-managed deployments:

- runtime receives the prompt artifact URL through environment config
- the platform exposes the prompt artifact in provenance views
- runtime admin overview reports the loaded `promptConfigLocation`
- platform verification checks that runtime prompt config location matches the expected artifact

You can see prompt rollout evidence in:

- `Overview`
- `Revisions`
- `Diagnostics`

## 9. Security And Access Rules

### 9.1 Editing and rebasing prompts

Requires deployment editor access or higher.

### 9.2 POC prompt preview

Still requires admin authorization because it sends runtime preview overrides directly.

That path depends on:

- `APP_ADMIN_API_KEY`
- runtime admin preview support

### 9.3 Normal deployed prompt use

Does not require admin preview access from the user.

The prompt artifact is part of the applied deployment configuration and is loaded by runtime at startup.

## 10. What The Platform UI Shows Now

### Deployments page

Shows curated module selection at deployment creation time.

### Prompts page

Shows:

- current prompt draft
- current curated module baseline
- baseline reapply controls
- latest published diff
- release prompt preview
- prompt revision snapshots
- POC preview path

### Overview/Revisions/Diagnostics

Show the prompt artifact alongside:

- actions artifact
- entity artifact
- routing artifact
- manifest artifact

## 11. Current Limitations

The current model is intentionally narrower than the future prompt-management roadmap.

What is supported now:

- curated module prompt presets
- deployment-owned prompt drafts
- deployment versioned prompt storage
- prompt artifact rollout to runtime
- session/request prompt preview for POC

What is not fully supported yet:

- editing every low-level framework prompt template individually
- browsing the full framework prompt file tree from the platform
- module-specific per-template prompt authoring UI
- arbitrary prompt-family/version management for all runtime internals
- merge-aware rebasing between curated modules

Right now the system manages a deployment-level prompt bundle, not the full internal prompt-template repository.

## 12. Recommended Usage Model

Use this model in production:

1. choose the closest curated module when creating the deployment
2. treat that as the baseline, not as the permanent fixed prompt source
3. make customer-specific edits in the deployment prompt draft
4. save, review, publish, and apply through normal release flow
5. use POC preview only for short-lived testing before publish

This keeps prompt behavior governed, versioned, and auditable.

## 13. Main Files Behind This Behavior

Platform backend:

- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentCuratedModuleCatalogService.java`
- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentService.java`
- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentArtifactService.java`
- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/RailwayProvisioningPlanService.java`
- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/deployment/service/DeploymentReleaseVerificationService.java`

Platform UI:

- `Platfrom/ui/src/pages/DeploymentsPage.tsx`
- `Platfrom/ui/src/pages/PromptsPage.tsx`
- `Platfrom/ui/src/pages/OverviewPage.tsx`
- `Platfrom/ui/src/pages/RevisionsPage.tsx`
- `Platfrom/ui/src/pages/DiagnosticsPage.tsx`

Runtime:

- `ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/config/RuntimeDeploymentPromptConfigService.java`
- `ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/ChatRuntimeController.java`
- `ai-infrastructure-module/ai-fabric-runtime/src/main/java/com/ai/fabric/runtime/web/admin/RuntimeAdminOverviewController.java`
