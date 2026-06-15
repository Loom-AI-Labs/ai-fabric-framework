# Security Policy

## Reporting Vulnerabilities

Please report suspected vulnerabilities privately using GitHub's [Private Vulnerability Reporting](https://github.com/loom-ai-labs/ai-fabric-framework/security/advisories/new) for this repository (Security tab → "Report a vulnerability"). Do not open a public issue containing exploit details, credentials, customer data, or private deployment information.

We aim to acknowledge new reports within a few business days.

When reporting, include:

- affected module or package
- framework version or commit
- impact summary
- reproduction steps if safe to share
- suggested mitigation if known

## Secret Handling

This repository must not contain:

- API keys
- access tokens
- private keys
- `.env` files
- customer-specific configuration
- private deployment URLs or credentials

Use environment variables or your own secret manager for provider credentials.
