# Behavior Churn Signals (Real_App)

Scenario: **behavior analytics + churn/sentiment insights** using AI Fabric’s behavior module.

This app is **fully offline**: it includes an in-app stub LLM provider and uses H2 with a small compatibility schema for the behavior tables.

## What this app proves

- `ai.behavior.enabled=true` activates the behavior module and loads a preset config
- An app can feed behavioral events via the `ExternalEventProvider` SPI
- `BehaviorAnalysisService` produces stored `BehaviorInsights` per user
- Query insights via REST (and built-in analytics endpoints)

## Run

1) Build framework artifacts:

`cd ai-infrastructure-module && mvn -DskipTests install`

2) Run the app:

`cd examples/real-apps/behavior-churn-signals && mvn -DskipTests package && java -jar target/*.jar`

App port: `8097`

## Demo endpoints

- `POST /api/demo/seed`
- `POST /api/behavior/analyze/{userId}`
- `POST /api/behavior/process-next`
- `GET /api/behavior/insights`
- Built-in module endpoints:
  - `GET /api/behavior/analytics/rapid-decline`
  - `GET /api/behavior/analytics/trend-distribution`

Use `requests/demo.http` to run the scenario.
