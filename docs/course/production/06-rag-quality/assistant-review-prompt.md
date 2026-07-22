# Independent Review Prompt: PROD-06

Review the PROD-06 checkpoint findings-first.

Verify:

- golden cases use the real application retrieval path;
- the request cannot select or forge a tenant;
- both tenants have positive and forbidden-ID proof;
- empty-index and missing-expected-source cases are distinct;
- freshness checks can catch both missing new content and returned old content;
- scorecard HTTP success is not mistaken for `passed=true`;
- prompt tests check resolution and slots without freezing answer prose or exposing prompt bodies;
- retrieval/provider failures return no canned answer;
- keyless tests and packaged ONNX/Lucene smoke are blocking;
- optional OpenAI evidence is separate and honestly labelled;
- no secret, raw internal note, tenant metadata, or provider detail leaks publicly.

Run `./mvnw --batch-mode --no-transfer-progress clean verify`, then the packaged smoke. Report file
and line references for every issue. Do not approve based only on unit tests or fluent model output.
