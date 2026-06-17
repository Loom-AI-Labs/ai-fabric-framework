# Marketplace Plugin Manifest Reference

Status: strict current-branch reference (2026-04-15)

This document describes the marketplace manifest contract enforced by the current platform implementation.

Source of truth:

- `Platfrom/backend/src/main/java/com/ai/fabric/platform/backend/marketplace/service/MarketplaceManifestService.java`

---

## 1) Supported Public Plugin Types

Current supported public plugin types:

- `TEMPLATE`
- `ACTION`
- `DATA`
- `INFERENCE_PROFILE`

Unsupported:

- `AUTOMATION`
- arbitrary shell/plugin code
- arbitrary runtime code
- `capabilityProfiles`

Important rule:

- `capabilityProfiles` is rejected by the current parser

---

## 2) Top-Level Fields

Required top-level fields:

- `schemaVersion`
- `pluginId`
- `version`
- `pluginType`
- `displayName`
- `compatibility`
- `pricing`
- `permissions`
- `contributions`

Current rules:

- `schemaVersion` must be `1`
- `pluginType` must match the catalog plugin type exactly
- `contributions` must be an object

Example skeleton:

```json
{
  "schemaVersion": 1,
  "pluginId": "mkp-action-example",
  "version": "1.0.0",
  "pluginType": "ACTION",
  "displayName": "Example Actions",
  "compatibility": {},
  "pricing": {
    "pricingModel": "FREE"
  },
  "permissions": {
    "contributesActions": true
  },
  "contributions": {
    "actions": []
  }
}
```

---

## 3) Compatibility Block

Supported fields:

- `minPlatformVersion`
- `maxPlatformVersion`
- `requiredCapabilities`
- `supportedDeploymentTargets`
- `supportedAuthModes`
- `supportedProviderModes`

### 3.1 `requiredCapabilities`

Supported values:

- `actions`
- `knowledgeSources`
- `shellConfig`
- `templates`
- `providers`

Normalization rule:

- parser normalizes hyphens and underscores

### 3.2 `supportedAuthModes`

Supported values:

- `PLATFORM_PROXY_SESSION`
- `PRIVATE_RUNTIME_BACKEND_MEDIATED`
- `PUBLIC_RUNTIME_AUTHENTICATED`
- `PUBLIC_RUNTIME_ANONYMOUS`

### 3.3 `supportedProviderModes`

Supported format:

- `key:value`

Supported keys:

- `llm`
- `embedding`
- `vector`
- `runtime`
- `connector`

Example:

```json
{
  "supportedProviderModes": [
    "llm:openai",
    "embedding:openai"
  ]
}
```

---

## 4) Pricing Block

Supported `pricingModel` values:

- `FREE`
- `ONE_OFF`
- `SUBSCRIPTION`

Rules:

- `FREE`
  - no amount or billing fields required
- `ONE_OFF`
  - requires positive `amount`
  - requires `currency`
  - does not allow `billingInterval` or `trialDays`
- `SUBSCRIPTION`
  - requires positive `amount`
  - requires `currency`
  - supports:
    - `billingInterval`: `MONTHLY` or `YEARLY`
    - `trialDays`: non-negative integer

Example:

```json
{
  "pricing": {
    "pricingModel": "SUBSCRIPTION",
    "amount": 29.0,
    "currency": "USD",
    "billingInterval": "MONTHLY",
    "trialDays": 7
  }
}
```

---

## 5) Install Form

`installForm` is optional and must be an array of objects.

Supported field types:

- `text`
- `url`
- `boolean`
- `select`
- `number`
- `secretRef`

Field contract:

- `id` required
- `label` optional
- `type` required
- `required` optional
- `description` optional
- `options` required for `select`

Example:

```json
{
  "installForm": [
    {
      "id": "provider",
      "label": "Notification provider",
      "type": "select",
      "required": true,
      "options": ["sendgrid", "twilio", "slack"]
    },
    {
      "id": "credentialSecretRef",
      "label": "Credential secret ref",
      "type": "secretRef",
      "required": true
    }
  ]
}
```

---

## 6) Permissions

Supported permission booleans:

- `contributesTemplate`
- `contributesActions`
- `contributesKnowledgeSources`
- `contributesProviders`
- `contributesShellPresentation`
- `requiresExternalHttpExecution`
- `requiresSharedDatasetAccess`
- `requiresDeploymentSecrets`

Validation rules:

- action contributions require `contributesActions = true`
- data contributions require `contributesKnowledgeSources = true`
- inference contributions require `contributesProviders = true`
- shell module or card contributions require `contributesShellPresentation = true`
- `secretRef` install-form fields require `requiresDeploymentSecrets = true`

---

## 7) `TEMPLATE` Contributions

Required block:

- `contributions.template`

Supported fields used by the current parser/compiler:

- `template.curatedModuleId`
- `template.security.authzMode`
- `template.recommendedPluginIds`
- `template.shell`

Current supported template security field:

- `authzMode`
  - must be supported by the managed deployment profile catalog

Shell fragment fields commonly used:

- `enabledModuleIds`
- `moduleRefs`
- `enabledCardIds`
- `cardRefs`
- greeting and starter prompt structures

Example:

```json
{
  "pluginType": "TEMPLATE",
  "permissions": {
    "contributesTemplate": true,
    "contributesShellPresentation": true
  },
  "contributions": {
    "template": {
      "curatedModuleId": "support",
      "recommendedPluginIds": ["mkp-data-help-center", "mkp-action-notifications"],
      "security": {
        "authzMode": "ALLOW_VERIFIED"
      },
      "shell": {
        "enabledModuleIds": ["docs", "ai-search", "actions", "support"]
      }
    }
  }
}
```

---

## 8) `ACTION` Contributions

Required block:

- `contributions.actions`

Validation rules:

- must be a non-empty array
- each action must declare `id` or `actionId`
- `route` may declare `url` or `path`, but not both
- if `route` is present, it must include at least one of `url` or `path`

Common action fields used by the current first-party manifests:

- `actionId`
- `displayName`
- `readOnly`
- `confirmationRequired`
- `adapterType`
- `route.method`
- `route.path`
- `route.url`

Example:

```json
{
  "pluginType": "ACTION",
  "permissions": {
    "contributesActions": true,
    "contributesShellPresentation": true,
    "requiresDeploymentSecrets": true
  },
  "contributions": {
    "actions": [
      {
        "actionId": "send-email",
        "displayName": "Send email",
        "readOnly": false,
        "confirmationRequired": true,
        "adapterType": "connector-http",
        "route": {
          "method": "POST",
          "path": "/actions/execute"
        }
      }
    ],
    "shell": {
      "moduleRefs": ["actions"]
    }
  }
}
```

---

## 9) `DATA` Contributions

Required blocks:

- `contributions.datasets`
- `contributions.knowledgeSources`

Optional blocks:

- `contributions.entityConfig`
- `contributions.shell`

### 9.1 Dataset contract

Each dataset must declare:

- `datasetId`
- `entityType`
- `storageScope`
- `sharingScope`
- `ingestionMode`
- `updateStrategy`

Current supported values:

- `storageScope`
  - `PLUGIN_SCOPED`
- `sharingScope`
  - `TENANT_SHARED`
- `ingestionMode`
  - `PACKAGED_SEED`
  - `EXTERNAL_SYNC_SQL`
  - `EXTERNAL_SYNC_FOLDER`
- `updateStrategy`
  - `UPSERT_BY_ID`

For `PACKAGED_SEED`:

- `seedDatasetRef` required

For external sync modes:

- `syncConnector` required

Supported sync connector types:

- `SQL_QUERY`
- `FILE_FOLDER`

### 9.2 Knowledge source contract

Each knowledge source must declare:

- `id` or `sourceKey`
- `adapterType` or `sourceType`
- `datasetRef`

Rules:

- if only one dataset exists, `datasetRef` may be omitted and is inferred
- if multiple datasets exist, `datasetRef` is required
- every `datasetRef` must point to a declared dataset

Example:

```json
{
  "pluginType": "DATA",
  "permissions": {
    "contributesKnowledgeSources": true,
    "contributesShellPresentation": true,
    "requiresSharedDatasetAccess": true
  },
  "contributions": {
    "entityConfig": {
      "ai-entities": {
        "support-policy": {
          "entity-type": "support-policy",
          "auto-embedding": true,
          "indexable": true,
          "enable-search": true
        }
      }
    },
    "datasets": [
      {
        "datasetId": "policy-folder-pack",
        "entityType": "support-policy",
        "storageScope": "PLUGIN_SCOPED",
        "sharingScope": "TENANT_SHARED",
        "ingestionMode": "EXTERNAL_SYNC_FOLDER",
        "updateStrategy": "UPSERT_BY_ID",
        "syncConnector": {
          "connectorType": "FILE_FOLDER",
          "folderRef": "classpath*:marketplace/folders/policy-pack/*.md"
        }
      }
    ],
    "knowledgeSources": [
      {
        "sourceType": "shared-index",
        "sourceKey": "policy-folder",
        "datasetRef": "policy-folder-pack",
        "entityType": "support-policy",
        "attributionLabel": "Policy folder marketplace data"
      }
    ],
    "shell": {
      "moduleRefs": ["docs", "ai-search", "support"]
    }
  }
}
```

---

## 10) `INFERENCE_PROFILE` Contributions

Required block:

- `contributions.inferenceProfile`

Required fields:

- `profileId` or `id`
- at least one of:
  - `orchestration`
  - `generation`
  - `embedding`

Common section fields:

- `provider`
- `endpointProfileRef`
- `baseUrl`
- `baseUrlField`
- `apiKeySecretRef`
- `apiKeySecretRefField`
- `deploymentName`
- `deploymentNameField`
- `apiVersion`
- `apiVersionField`
- `model`
- `modelField`
- `maxTokens`
- `maxTokensField`
- `temperature`
- `temperatureField`
- `timeout`
- `timeoutField`
- embedding-only fields such as `dimensions`

Example:

```json
{
  "pluginType": "INFERENCE_PROFILE",
  "permissions": {
    "contributesProviders": true,
    "requiresDeploymentSecrets": true
  },
  "contributions": {
    "inferenceProfile": {
      "profileId": "customer-openai",
      "generation": {
        "provider": "openai",
        "baseUrlField": "baseUrl",
        "apiKeySecretRefField": "apiKey",
        "modelField": "generationModel",
        "model": "gpt-4.1-mini",
        "maxTokens": 1800,
        "temperature": 0.3,
        "timeout": 60
      },
      "embedding": {
        "provider": "openai",
        "baseUrlField": "baseUrl",
        "apiKeySecretRefField": "apiKey",
        "modelField": "embeddingModel",
        "model": "text-embedding-3-small",
        "dimensions": 1536
      }
    }
  }
}
```

---

## 11) Recommended Validation Flow

For every new manifest:

1. submit the plugin version
2. run submission validation
3. publish the version
4. install it onto a test deployment
5. resolve the install into the active draft
6. run deployment draft validation
7. publish and apply the deployment
8. verify the resulting runtime behavior

Use:

- `./MARKETPLACE_PLUGIN_VERIFICATION_AND_TROUBLESHOOTING_GUIDE.md`
