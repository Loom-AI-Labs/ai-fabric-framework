# Failure And Troubleshooting

## Intentional Failure

Symptom: semantic search returns no support evidence after database seed.

Cause to test first: the records were not indexed into the configured vector space.

Correct boundary: application indexing lifecycle and AI Fabric vector operation, not browser
presentation or generic model generation.

Proof: readiness distinguishes five source records from zero vectors; after indexing, the same golden
query returns the expected evidence ID.

## Other Diagnostic Branches

- Missing ONNX files: provider startup/configuration problem.
- Model/dimension mismatch: embedding/vector compatibility problem.
- Wrong Lucene path: environment/index selection problem.
- Metadata missing: application projection/index lifecycle problem.
- Answer appears with no evidence: hidden fallback or application-authored response; stop and inspect.

