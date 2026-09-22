# Governed Actions Quickstart

A minimal Spring Boot example demonstrating governed AI Fabric actions using artifacts published to Maven Central.

The example runs without API keys by default and demonstrates:

- automatic `@AIAction` discovery
- a read-only action
- a confirmation-required write action
- parameter extraction
- required parameter handling
- confirmation and rejection
- safe handling of invalid input
- post-action execution results

## Requirements

- Java 21
- Maven 3.9+

Check your environment:

```bash
java -version
mvn -version
```

## Run the quickstart

From this directory:

```bash
mvn clean test
mvn spring-boot:run
```

The application starts on:

```text
http://localhost:8080
```

No API key is required.

The default `QuickstartAIProvider` is a deterministic local provider used only by this example so the complete governed-action flow can be demonstrated without external services.

## Available actions

The application defines two actions.

### `get_account`

A read-only action:

```java
@AIAction(
    name = "get_account",
    accessMode = ActionAccessMode.READ_ONLY,
    requiresConfirmation = false
)
```

It returns demo account information immediately.

### `update_email`

A write action:

```java
@AIAction(
    name = "update_email",
    accessMode = ActionAccessMode.WRITE_ONLY,
    requiresConfirmation = true
)
```

It requires an `email` parameter and explicit user confirmation before execution.

## Inspect registered actions

```bash
curl http://localhost:8080/api/actions
```

The response should contain both:

```text
get_account
update_email
```

## Read action

Send:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Show my account",
    "sessionId": "read-session",
    "conversationId": "read-conversation"
  }'
```

Expected result:

```text
ACTION_EXECUTED
```

with a message similar to:

```text
Account retrieved
```

No confirmation is required because the action is read-only.

## Confirmation-required write action

Start the update:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Change my email to test@gmail.com",
    "sessionId": "confirm-session",
    "conversationId": "confirm-conversation"
  }'
```

Expected result:

```text
CONFIRMATION_REQUIRED
```

with a confirmation message similar to:

```text
Change your email to test@gmail.com?
```

The action has not executed yet.

## Confirm the action

Use the same conversation identifiers:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "yes",
    "sessionId": "confirm-session",
    "conversationId": "confirm-conversation"
  }'
```

Expected result:

```text
ACTION_EXECUTED
```

with:

```text
Email updated to test@gmail.com
```

The response also contains the action result as post-action evidence.

## Reject the action

First create another pending action:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Change my email to reject@test.com",
    "sessionId": "reject-session",
    "conversationId": "reject-conversation"
  }'
```

Then reject it:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "no",
    "sessionId": "reject-session",
    "conversationId": "reject-conversation"
  }'
```

The pending action is rejected and `update_email` is not executed.

## Missing parameter

Send a request without an email value:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Change my email",
    "sessionId": "missing-session",
    "conversationId": "missing-conversation"
  }'
```

Expected result:

```text
CLARIFICATION_REQUIRED
```

with:

```text
To proceed, please provide: email.
```

The action is not executed.

## Invalid parameter

Send an invalid email:

```bash
curl -X POST http://localhost:8080/api/actions/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Change my email to invalid-email",
    "sessionId": "invalid-session",
    "conversationId": "invalid-conversation"
  }'
```

Expected result:

```text
CLARIFICATION_REQUIRED
```

The invalid value is not accepted as an executable email parameter and the action is not executed.

## How the flow works

```text
User request
    |
    v
AI Fabric intent extraction
    |
    v
@AIAction selected
    |
    v
@Param values extracted
    |
    v
Required parameters validated
    |
    +---------------------------+
    |                           |
READ action                 WRITE action
    |                           |
    v                           v
execute()               confirmation required
                                |
                        +-------+-------+
                        |               |
                       yes              no
                        |               |
                        v               v
                    execute()         reject
```

Application code never calls the action handlers directly from the controller.

The controller sends the user query to AI Fabric's orchestration pipeline. AI Fabric discovers the annotated actions, selects the appropriate action, extracts its parameters and applies confirmation rules before invoking `@ActionExecute`.

## Offline provider

`QuickstartAIProvider` is deliberately small and deterministic.

It exists so this quickstart can run:

- without an OpenAI key
- without network access
- without private credentials
- without depending on local AI Fabric reactor modules

It only understands the demo requests used by this quickstart and is not intended to replace a real language model.

## Using a real provider

To experiment with a real model, add the Maven Central provider dependency:

```xml
<dependency>
    <groupId>io.github.loom-ai-labs</groupId>
    <artifactId>ai-fabric-provider-spring-ai</artifactId>
</dependency>
```

Then configure OpenAI instead of the deterministic quickstart provider:

```yaml
ai:
  providers:
    llm-provider: openai
    openai:
      enabled: ${OPENAI_ENABLED:false}
      api-key: ${OPENAI_API_KEY:}
      base-url: ${OPENAI_BASE_URL:https://api.openai.com/v1}
      model: ${OPENAI_MODEL:gpt-4o-mini}
```

Export credentials outside source control:

```bash
export OPENAI_ENABLED=true
export OPENAI_API_KEY="<your key>"
```

Do not commit API keys.

## Test

Run:

```bash
mvn clean test
```

The tests cover the two action handlers and the deterministic intent provider.

## Maven Central

The quickstart uses AI Fabric `0.5.3` artifacts under:

```text
io.github.loom-ai-labs
```

It does not depend on local reactor modules from the AI Fabric repository.