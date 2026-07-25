# CASE-01 Reproduction Prompt

Reproduce the AI Shopping Experience against AI Fabric 0.4.0. Read the lesson and the
`chat-capabilities-demo` README first. Do not change framework or application code.

Use the public session-isolated demo, or run the focused real-app Maven tests locally. Prove:

- no-evidence versus staged product/review evidence;
- two explicit product attachments and a short follow-up;
- backend-owned conversation continuity;
- add-to-cart proposal, reject with zero mutation, and confirm with exactly one mutation;
- user-facing action projection without raw nested payloads.

Capture evidence IDs, conversation ID continuity, cart state before/after, backend health/version,
and any failure. Never claim an answer is grounded because it sounds correct. Never print an API key.
Finish with PASS, FAIL, or NOT RUN for each proof.
