# Deployment Export, Import, Sealed Backup, And Restore Guide

Status: Platform-V10 implementation guide  
Audience: Platform admins/operators and deployment admins  

## What This Feature Does

Platform can export a managed deployment into a portable bundle and import that bundle back as either:

- a clone deployment draft, or
- a restore-in-place draft on the same deployment id.

Exports are safe by default:

- `CONFIG_ONLY` exports contain deployment configuration and secret references only.
- `SEALED_BACKUP` exports include deployment-scoped secret values only inside an encrypted `secretEnvelope`.
- Secret values are never rendered in the UI, audit logs, or config-only bundles.

Imports never apply live production changes directly. They create drafts that must still go through publish, apply, and verification gates.

## API Endpoints

All endpoints require a Platform-authenticated actor with deployment-admin access for the target deployment.
Platform admins have deployment-admin access globally; platform operators or customer admins must still pass the deployment ownership/assignment check enforced by the backend service.

### Preview Export

```http
POST /api/deployments/{deploymentId}/export/preview
Content-Type: application/json

{
  "exportMode": "CONFIG_ONLY"
}
```

Use `SEALED_BACKUP` to preview which secret references would be sealed.

### Export Bundle

Config-only:

```http
POST /api/deployments/{deploymentId}/exports
Content-Type: application/json

{
  "exportMode": "CONFIG_ONLY",
  "reason": "operator backup"
}
```

Sealed backup:

```http
POST /api/deployments/{deploymentId}/exports
Content-Type: application/json

{
  "exportMode": "SEALED_BACKUP",
  "reason": "disaster recovery backup",
  "recipient": {
    "type": "OPERATOR_PUBLIC_KEY",
    "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----"
  }
}
```

### Preview Import

```http
POST /api/deployment-imports/preview
Content-Type: application/json

{
  "bundle": { "...": "downloaded bundle JSON" },
  "importMode": "CONFIG_ONLY_CLONE",
  "newDeploymentName": "Imported deployment",
  "targetEnvironment": "staging"
}
```

For sealed clone or restore, include `privateKeyPem` so Platform can validate the secret envelope.

### Import Bundle

Clone:

```http
POST /api/deployment-imports
Content-Type: application/json

{
  "bundle": { "...": "downloaded bundle JSON" },
  "importMode": "CONFIG_ONLY_CLONE",
  "newDeploymentName": "Imported deployment",
  "targetEnvironment": "staging",
  "reason": "restore smoke"
}
```

Restore in place:

```http
POST /api/deployment-imports
Content-Type: application/json

{
  "bundle": { "...": "downloaded bundle JSON" },
  "importMode": "RESTORE_IN_PLACE",
  "targetDeploymentId": "dep-...",
  "privateKeyPem": "-----BEGIN PRIVATE KEY-----\n...\n-----END PRIVATE KEY-----",
  "reason": "restore same runtime contract"
}
```

## Secret Rules

Export classifications:

- `SEALED_EXPORTABLE`: deployment-scoped or explicitly bound secret values can be sealed.
- `REGENERATE_RECOMMENDED`: restore should supply or rotate the value.
- `FORBIDDEN`: never exported, including customer OAuth/session/checkout/temp materials.
- `ENVIRONMENT_BOUND`: remap in the target environment.
- `MISSING_REFERENCE`: referenced config has no Platform secret value.

Sealed bundles use envelope encryption:

- AES-256-GCM encrypts the secret payload.
- RSA-OAEP-SHA256 wraps the AES key.
- Manifest hash and source deployment id are authenticated as AAD.

## UI Workflow

Open Platform UI, select a deployment, then go to `Revisions`.

The `Deployment backup and restore` card supports:

- preview export,
- download config-only bundle,
- download sealed bundle after reason + public key,
- upload/paste bundle JSON,
- preview import/restore,
- create import draft.

The UI never shows decrypted secret values.

## Restore Semantics

`RESTORE_IN_PLACE`:

- preserves deployment id,
- preserves runtime assertion audience,
- preserves the intended runtime route contract,
- creates a new active draft,
- optionally restores sealed secrets to the same deployment scope.

`CONFIG_ONLY_CLONE` and `SEALED_CLONE`:

- create a new deployment id,
- create a draft copied from the bundle,
- require the customer/integration to update runtime URL/audience unless a stable alias is configured.

## Verification Commands

Focused backend tests:

```bash
mvn -f Platfrom/backend/pom.xml -q -Dtest=DeploymentBundleSealingServiceTest,DeploymentBundleExportImportServiceTest test
```

Backend compile:

```bash
mvn -f Platfrom/backend/pom.xml -q compile
```

UI build:

```bash
npm --prefix Platfrom/ui run build
```

Security checks to keep true:

- config-only bundle response does not contain secret values,
- sealed bundle response contains `secretEnvelope` but not plaintext secret values,
- wrong private key fails closed,
- import creates a draft only,
- restore-in-place does not change deployment id.
