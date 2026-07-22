# Independent Review Prompt: PROD-02 Modes And Positions

Review the diff from `course-0.3.3-p01-provider-routing` to
`course-0.3.3-p02-modes-positions` findings-first.

Verify:

- no-hint Core action behavior is preserved;
- positions are mapped only in application code;
- explicit mode is still validated by Core strict routing;
- unknown modes and positions fail visibly;
- `actions-enabled`, not prompt wording, blocks writes;
- HTTP input cannot supply trusted identity, tenant, history, or pending work;
- policy metadata proves the effective mode, position, and capabilities;
- tests use provider-boundary structured fixtures rather than application text matching;
- `clean verify` and packaged ONNX/Lucene smoke pass.

List correctness or security findings before summary. Do not approve based only on compilation.
