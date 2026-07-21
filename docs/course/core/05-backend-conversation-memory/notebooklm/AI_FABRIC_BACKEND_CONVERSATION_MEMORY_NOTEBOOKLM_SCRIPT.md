# NotebookLM Single-Source Production Script: Backend-Owned Conversation Memory

## Generator Instructions - Do Not Narrate

Use this file as the only source for the video. Do not supplement it with general chatbot memory,
browser state, or database design knowledge. Do not ask for or rely on another source.

Create a structured technical explainer titled **Backend-Owned Conversation Memory With AI Fabric:
History, Follow-Ups, And Pending Actions**. Follow the fourteen scenes in order. Use every **Visual**
block as production direction and every **Narration** block as the spoken message. Natural
transitions are allowed, but do not omit, replace, or contradict the technical content.

This is the theoretical introduction to CORE-05, not a code-along. Keep AI Fabric's current
`ai-fabric-chat-session` data model, pipeline steps, ownership checks, memory bounds, and
confirmation state as the subject. Do not invent storage behavior, session expiry, context windows,
endpoints, provider behavior, test output, or guarantees. Apply the final accuracy guardrails to the
complete output.

## Production Direction

- Title: **Backend-Owned Conversation Memory With AI Fabric: History, Follow-Ups, And Pending
  Actions**
- Target duration: 12-15 minutes.
- Audience: Java and Spring Boot developers who have registered governed actions in CORE-04.
- Voice: direct, practical, calm, and technically precise. Address the developer as **you**.
- Primary objective: explain how the backend owns turns, resolved targets, action drafts, and pending
  confirmations while the UI sends only the current message and stable conversation identity.
- Example application: the continuing Spring Boot Support Knowledge Assistant.
- Example sequence: **Why is ticket T-1042 unresolved?** -> **escalate it** -> **yes**.
- Visual style: use one persistent conversation timeline, a backend state box, bounded memory
  windows, and cross-user denial branches. Avoid a browser-local transcript as the architecture.

## Scene 1: A Follow-Up Needs More Than The Current Sentence

**Visual:** Show three user turns. Highlight that the final two are ambiguous alone.

```text
Turn 1: "Why is ticket T-1042 unresolved?"
Turn 2: "escalate it"
Turn 3: "yes"
```

Then reveal backend state beneath the timeline.

```text
recent turns + resolved ticket target + pending escalate action
```

**Narration:**

The sentence "escalate it" does not identify a ticket by itself. The word "yes" does not identify
what the user approved. A multi-turn assistant must connect each new message to authoritative state
from the same conversation.

AI Fabric's chat-session module stores recent turns, bounded target context, action drafts, and
pending confirmation state in the backend. That state is loaded into orchestration before the next
intent is handled.

This is not an instruction to send every historical token forever. It is a controlled memory window
owned by the application backend. The browser displays a conversation, but it does not become the
source of truth for what was said, which record was selected, or which write awaits confirmation.

## Scene 2: The Client Sends One New Message And Stable Identity

**Visual:** Compare two request shapes.

```text
Preferred
---------
conversationId: conv-42
authenticated owner: user-17   <- derived by backend security
message: "escalate it"
current attachments: optional

Avoid as authority
------------------
historyMessages: [browser-rebuilt transcript]
pendingAction: {browser-rebuilt command}
ownerId: arbitrary user input
```

**Narration:**

For each turn, the UI should send the new message, the stable conversation identifier, and any
attachments selected for this request. The server derives the current owner from authenticated
identity and constructs the orchestration context.

The UI does not need to resend earlier chat turns. It must not rebuild a pending action and present
that payload as authoritative confirmation. It must not choose another owner's identity through a
request field.

This design has practical benefits. A page refresh does not erase the conversation. Two clients can
continue the same authorized session. A manipulated browser transcript cannot rewrite what the
backend remembers. Prompt construction remains centralized and bounded instead of being duplicated
across web, mobile, and API clients.

The UI may cache messages for presentation speed, but backend state decides AI context and action
continuity.

## Scene 3: Enabling Memory Activates A Required Backend Contract

**Visual:** Show the current module and bean requirements.

```text
dependency: ai-fabric-chat-session

ai.chat.enabled: true
  -> ChatSessionStorageProvider required
  -> ChatSessionAccessControlPolicy required
  -> SlidingWindowMemoryStrategy default
  -> enrichment, confirmation resolution, recording, target seeding
```

**Narration:**

Conversation memory is an optional module. Adding `ai-fabric-chat-session` and enabling
`ai.chat.enabled` activates its Spring configuration.

The module requires a `ChatSessionStorageProvider` and a `ChatSessionAccessControlPolicy`. With JPA
available, the framework can provide its default database storage. The required-beans validator
fails startup when enabled chat has no storage provider. Spring also cannot construct the chat
service without the access policy.

The only built-in memory strategy in this release is a sliding window. The module registers pipeline
steps for conversation enrichment, confirmation resolution, conversation recording, and working-set
target seeding. It also provides backend stores for pending actions and incomplete action drafts.

Enabling chat is therefore not a prompt toggle. It adds persistence and authorization obligations to
the application.

## Scene 4: Sessions And Turns Have Different Responsibilities

**Visual:** Show the JPA data model.

```text
chat_sessions
  id = conversationId
  ownerId
  status = ACTIVE | CLOSED
  createdAt
  lastInteractionAt
  sessionMetadata
       |
       +---- chat_turns, ordered by timestamp
               userQuery
               aiResponse
               turnMetadata
```

**Narration:**

The default database model has a `ChatSession` and ordered `ChatTurn` records.

The session owns the conversation ID, owner ID, `ACTIVE` or `CLOSED` status, creation time, last
interaction time, turns, and session metadata. Session metadata holds bounded conversational state
such as the pending-action stack, drafts, and recently pinned targets.

Each turn stores the user query, assistant response, timestamp, and structured turn metadata. That
metadata can preserve bounded action summaries, safe references, and working-set document IDs. When
history is converted to model messages, AI Fabric creates separate user and assistant role messages;
it does not flatten the complete transcript into one user string.

This is conversation memory, not a knowledge base. Long-lived policies and support articles belong
in approved source storage and vector indexing, not in chat-session metadata.

## Scene 5: Ownership Is Checked Before History Is Loaded Or Written

**Visual:** Show four policy calls around a session.

```text
canCreateConversation(owner)
canAccessConversation(owner, conversation)
canRecordTurn(owner, conversation)
canDeleteConversation(owner, conversation)
```

Place an additional equality check inside the session: `session.ownerId == current owner`.

**Narration:**

`ChatSessionAccessControlPolicy` is the application hook for creating, reading, recording, and
deleting conversations. The service invokes the relevant policy and then verifies that the stored
session belongs to the current owner.

A policy grant alone cannot make a session owned by someone else readable. A mismatched owner
produces `ChatSessionAccessDeniedException`. Loading and recording also reject a blank owner.

When automatic creation is enabled, the service still calls `canCreateConversation` before creating
an active session. Deletion first verifies ownership when the session exists and then applies the
delete policy.

The application should implement these methods using authenticated identity and any tenant or role
rules required by the product. A policy that always returns true may be useful in a narrow local
test, but it is not a production access model.

## Scene 6: Conversation Enrichment Runs Before Intent Handling

**Visual:** Place the step in the request pipeline.

```text
security analysis (10)
  -> access control (20)
  -> conversation enrichment (25)
  -> PII input processing (30)
  -> intent and retrieval work
```

Show `historyMessages` and chat diagnostics being added to `PipelineContext`.

**Narration:**

For an enabled conversation, `ConversationEnrichmentStep` runs after request access control and
before PII input processing and intent handling.

It takes the conversation ID and current backend identifier, asks `ChatSessionService` for recent
messages, and adds those role-aware messages to `PipelineContext`. It also records diagnostics such
as message count, character count, memory strategy, and configured window size.

An explicit conversation access denial terminates orchestration with `ACCESS_DENIED`. Other storage
or enrichment failures are currently logged and leave the request without loaded history. Your
operational tests should make that degraded path visible when conversation continuity is required.

The current user message remains the current query. History is a separate field used by structured
prompt construction; it is not concatenated by the frontend into a larger pretend query.

## Scene 7: Memory Is Bounded By Turns And Characters

**Visual:** Show a long timeline narrowed in two stages.

```text
all stored turns
  -> latest ai.chat.window-size turns, default 10
  -> convert each turn to user + assistant messages
  -> drop oldest whole messages until max-context-chars, default 8000
  -> current prompt history
```

**Narration:**

Backend persistence and prompt context are not the same size.

The default sliding-window strategy selects the latest configured number of turns. A turn can
produce a user message and an assistant message, including bounded action or working-set references
from turn metadata. The service then enforces `max-context-chars` by dropping the oldest whole
messages until the history fits. It never keeps an arbitrary substring of an old message.

The default window is ten turns and the default character cap is eight thousand. Both are
configuration values, not universal model limits. The window can be set to zero to provide no
history, and validation places an upper bound of fifty turns.

These controls limit cost and stale context. They do not summarize unlimited history, and they do
not guarantee that a fact from an early conversation remains available forever.

## Scene 8: Recent Targets Are Structured Memory, Not Text Guessing

**Visual:** Show an attachment or action result becoming a `ResolvedTarget`, then being persisted in
session metadata.

```text
fresh request attachment or explicit ActionTargetRef
  -> resolved target
  -> bounded session metadata
  -> reused only when the next request has no new attachment
  -> expires from reuse after N turns, default 3
```

**Narration:**

Follow-ups often refer to a selected record rather than only to words in the transcript. AI Fabric
can persist recently resolved targets in session metadata.

Fresh request attachments and explicit action-result targets are normalized into bounded records
containing approved identifiers, vector space, content text, source, and limited metadata. On a
later request with no new attachment or already-resolved target, enrichment can seed those targets
back into the pipeline.

Reuse is turn-bounded. The default `pinned-target-reuse-window-turns` is three. Fresh targets replace
the prior stored selection, and simply reusing an old target does not extend its lifetime
indefinitely.

This gives "open it" or "escalate that ticket" a structured candidate. It is still not authorization.
The target must pass the current request's tenant, access, evidence, and action checks.

## Scene 9: Incomplete Actions And Pending Actions Are Different State

**Visual:** Place two separate stacks beside the conversation.

```text
Action draft                          Pending action
------------                          --------------
intent chose action                   parameters are complete
required data missing                 authorization passed
stores partial safe params            awaiting yes/no
next turn may complete it             next turn may confirm/reject
```

**Narration:**

AI Fabric distinguishes an incomplete action draft from a pending confirmation.

When a selected action lacks required user information, the framework can store an `ActionDraft`
with the action name, existing parameters, missing summary, and timestamps. A later turn can supply
the missing value without asking the browser to resend the partial command.

A `PendingAction` is created only after the action has enough validated information and has passed
authorization. It stores the exact effective proposal awaiting approval, including trusted evidence
needed for its target.

Keeping these states separate prevents "yes" from approving a proposal that was never complete. It
also lets clarification UI remain a presentation of backend state instead of an independent form
engine that invents action continuity.

## Scene 10: Confirmation Resolution Uses The Same Conversation Boundary

**Visual:** Animate the third turn in the example.

```text
conversation conv-42, owner user-17

pending: escalate_ticket(ticketId=T-1042)
new message: "yes"
  -> intent contains positive confirmation
  -> resolver reads owner-scoped pending action
  -> pop pending + mark confirmed
  -> normal action handler path
```

**Narration:**

`ConfirmationResolutionStep` runs after intent extraction and before `IntentHandlingStep`. It loads
session metadata for the same conversation and owner, then evaluates registered confirmation
resolvers by priority.

An ordinary positive confirmation resolves the current pending action, consumes it, marks the
action confirmed in pipeline context, and preserves its trusted evidence. Compound, configured, and
annotated resolvers can support reviewed multi-action flows. An expired resolver removes a stale
pending proposal before it can execute.

The word yes has no authority without matching backend state. If the UI sends it under a new
conversation ID, another owner, or after the pending action has expired, the original action is not
available for execution.

## Scene 11: Record The Sanitized Outcome After Processing

**Visual:** Place recording late in the pipeline.

```text
intent/retrieval/action result
  -> response sanitization (90)
  -> conversation recording (95)
  -> history persistence (100)
```

Show `queryPersistenceMode = NEVER_PERSIST` bypassing enrichment and recording.

**Narration:**

`ConversationRecordingStep` runs after response sanitization. It records the current user query and
the sanitized assistant message, then stores bounded turn metadata for result type, action outcome,
safe references, and the retrieval working set.

When the PII service is available, recording redacts the query again before persistence. A request
marked with the `NEVER_PERSIST` query-persistence mode bypasses conversation enrichment and
recording. This supports transient workflows that must not enter chat memory.

Only nonblank user and assistant text is recorded, and only active sessions accept new turns. A
recording failure is logged and currently does not replace the already-produced response with a
successfully-persisted claim. If durable memory is a product requirement, monitor and test storage
failures rather than assuming every visible answer became a stored turn.

## Scene 12: Treat Three Kinds Of Expiration Separately

**Visual:** Show three clocks.

```text
prompt history window       target reuse window       pending confirmation
latest N turns/chars        default 3 turns           default 5 minutes

chat session lifecycle
ACTIVE | CLOSED + lastInteractionAt -> application retention/cleanup policy
```

**Narration:**

Conversation state has several different lifetimes.

Prompt history is bounded by the turn and character windows. Pinned targets are reusable for a
configured number of turns. Pending confirmations have a built-in default timeout of five minutes
through the confirmation resolver.

The chat-session module does not currently impose a universal time-to-live that deletes every
session. A session stores `lastInteractionAt` and `ACTIVE` or `CLOSED` status, and the repository can
locate inactive sessions. Your application or operating environment must define retention,
closure, archival, and cleanup according to product and privacy policy.

Do not describe a target leaving the prompt window as deletion. Do not describe an expired pending
action as deletion of the transcript. And do not keep sensitive conversation records forever merely
because the framework does not choose the business retention period for you.

## Scene 13: Prove Continuity And Isolation With Failure Cases

**Visual:** Present a test matrix.

| Scenario | Expected observation |
| --- | --- |
| Same owner and conversation | Recent turns enrich the new request |
| New conversation ID | Prior context and pending action are absent |
| Different owner | Access denied before history is exposed |
| Window exceeded | Oldest context is omitted |
| New attachment supplied | Old pinned target is not reused as current selection |
| Target reuse window exceeded | Old target is unavailable |
| Pending action rejected | No execution and pending state removed |
| Pending action expired | No execution and stale top action cleared |
| `NEVER_PERSIST` request | No enrichment and no new stored turn |
| Closed session | New turn is not recorded |

**Narration:**

The memory test suite should prove both continuity and isolation.

Use a multi-turn integration test where the second request sends only its new message and resolves
the prior ticket from backend state. Confirm a pending action on the third turn. Then repeat with a
new conversation ID and prove the context is unavailable.

Create another user and assert that reading, recording, or deleting the first user's conversation
fails. Exercise the configured turn, character, target, and pending-action boundaries. Verify that
transient requests do not persist and that a closed session does not accept another recorded turn.

Finally, inspect the actual client request. A passing LLM response is not proof of backend-owned
memory if the browser secretly resent the transcript.

## Scene 14: The Ownership Map And Completion Proof

**Visual:** End with an ownership table followed by a checklist.

| Concern | Owner |
| --- | --- |
| Current message and current UI attachments | Client request |
| Authenticated owner identity | Application security layer |
| Conversation, turns, metadata, drafts, pending actions | AI Fabric chat-session storage |
| Access to create/read/write/delete conversation | Application `ChatSessionAccessControlPolicy` |
| Prompt memory window and target reuse | AI Fabric configuration and strategy |
| Session retention, closure, archival, and cleanup | Application and operations |
| Panel visibility, scroll position, dismissed cards | Client presentation state |

```text
Done when:
[ ] the UI sends only the new message and stable conversation identity
[ ] backend history supplies bounded role-aware messages
[ ] follow-up target reuse is structured and turn-bounded
[ ] action drafts and pending confirmations survive the next authorized turn
[ ] cross-owner access fails before history is exposed
[ ] transient requests do not persist
[ ] session cleanup policy is explicit rather than implied
```

**Narration:**

Backend-owned memory gives AI Fabric enough trusted context to interpret short follow-ups without
turning the browser into a prompt database.

You have completed this foundation when the same authorized conversation can explain a ticket,
resolve "escalate it," and confirm with "yes" while each request carries only the new message.
History is bounded, targets are explicit, pending actions are backend state, and another owner cannot
cross the conversation boundary.

The next lesson applies the same ownership principle to tenant security and privacy: identity and
evidence scope must be established before retrieval, generation, or action execution can expose
anything.

## Accuracy Guardrails - Do Not Narrate

1. Do not say the browser owns authoritative conversation history or pending actions.
2. Do not advise accepting an owner or tenant identifier as trusted merely because the client sent
   it; derive identity from application authentication.
3. Do not say enabling `ai.chat.enabled` is only a prompt option. It requires storage and an access
   policy.
4. Do not invent a memory strategy. `SLIDING_WINDOW` is the only built-in strategy in this release.
5. Do not say the default window of ten turns means ten messages; one turn can produce user and
   assistant messages.
6. Do not claim the character cap truncates arbitrary message text. The service drops oldest whole
   messages.
7. Do not conflate conversation turns, pinned targets, action drafts, and pending actions. They have
   different contracts and lifetimes.
8. Do not say a pinned target authorizes access or execution. Current security and action checks
   still apply.
9. Do not claim every chat session expires after five minutes. The five-minute default applies to
   pending confirmations.
10. Do not claim AI Fabric currently provides universal session TTL deletion. Retention and cleanup
    are application or operational policy.
11. Do not say `NEVER_PERSIST` merely hides UI history; it disables conversation persistence for the
    request path.
12. Do not claim every recording succeeds because the user received a response. Monitor and test
    storage failures when durable memory is required.
13. Do not mix browser presentation state, such as a closed panel, with backend conversation state.
14. Do not prove memory with one request or a browser-supplied transcript. Use multi-turn backend and
    cross-owner tests.
