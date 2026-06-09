# Relationship Query CRM Insights (Real_App)

Scenario: **natural language → JPQL** using AI Fabric’s Relationship Query module, over a realistic CRM schema.

This app is **fully offline**: it includes an in-app stub LLM provider so you can run the module without external keys.

## What this app proves

- Relationship Query auto-discovers entity schema via JPA metamodel + `@AICapable`
- A single request (`/api/crm/query`) produces a structured plan + JPQL execution + ID results
- No vector DB / embeddings required for this scenario (pure relational traversal)

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/relationship-query-crm-insights && mvn -DskipTests package && java -jar target/*.jar`

App port: `8096`

## Demo endpoints

- `POST /api/demo/seed`
- `GET /api/crm/schema`
- `POST /api/crm/query`

Use `requests/demo.http` to run the scenario.
