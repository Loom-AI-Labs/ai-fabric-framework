# Contributing

Thank you for helping improve AI Fabric Framework.

## Development Setup

Requirements:

- Java 21
- Maven 3.9+

Run the framework build:

```bash
mvn -f ai-infrastructure-module/pom.xml clean verify
```

For a faster local compile:

```bash
mvn -f ai-infrastructure-module/pom.xml -DskipTests compile
```

## Contribution Guidelines

- Keep framework code generic and product-neutral.
- Do not add product-specific deployment, customer, billing, or operator workflows to this repo.
- Do not commit secrets, `.env` files, private tokens, private keys, or customer configuration.
- Prefer small, focused changes with tests.
- Keep public docs aligned with implemented framework behavior.

## Pull Requests

A good pull request should include:

- a clear problem statement
- the smallest coherent implementation
- tests or a clear reason tests are not needed
- public documentation updates when behavior or usage changes

## Module Boundaries

Framework modules should expose reusable contracts and primitives. Productized deployment, governance UX, customer operations, commercial packaging, and managed platform workflows belong outside this public framework repo.
