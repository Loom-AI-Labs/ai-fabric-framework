# Security Policy

## Reporting Vulnerabilities

Please report suspected vulnerabilities privately to the repository owner. Do not open a public issue containing exploit details, credentials, customer data, or private deployment information.

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
