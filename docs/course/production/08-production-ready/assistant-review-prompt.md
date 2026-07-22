# Independent Review Prompt: PROD-08

Review the PROD-08 checkpoint findings-first.

Verify:

- all previous course behavior still passes;
- the Docker build runs tests and the runtime image is non-root;
- image revision, API commit, and checked-out commit match;
- `.git` and credentials are absent from the image and reports;
- database, vector, session, indexing, migration, and provider readiness are independent;
- disabled optional generation is not misreported as a failure or a live-provider pass;
- the application database, Qdrant evidence, backend chat, migration, and indexing state survive an
  application-only restart without reseeding;
- the quality scorecard passes after restart;
- release-probe text states that no model ran;
- cleanup requires admin scope and explicit enablement;
- retention removes only eligible operational rows/sessions and preserves source rows/vectors;
- selected OpenAI without a key fails startup with the validator error and no fallback;
- optional keyed evidence is `PASS`, `FAIL`, or `NOT_RUN` in a separate artifact;
- scripts clean up containers, networks, volumes, and images on failure;
- CI retains exact-commit reports.

Run the clean Java suite, packaged Lucene smoke, Docker Qdrant smoke, and release-container smoke.
Cite file/line and JSON evidence for every finding. Do not approve from Actuator health alone.
