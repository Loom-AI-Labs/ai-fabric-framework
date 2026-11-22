# Rich Document Readers

Optional PDF, Word and scanned-image extraction for the existing Spring AI
document-indexing adapter. Add `ai-fabric-document-readers` at the same framework
version as `ai-fabric-indexing`. It does not upload, grant access, embed or index
a file by itself.

```java
var options = DocumentReadOptions.defaults();
var ocr = new TesseractOcrEngine(Path.of("/usr/bin/tesseract"), Set.of("eng"));
var reader = new RichDocumentReader(new FileSystemResource(file),
    SpringAiTrustedResourcePolicy.trustedRoot(approvedTemporaryRoot), "pdf", options, ocr);
var documents = reader.read();
var diagnostics = reader.report();
```

PDF uses PDFBox. DOCX/DOC use Apache POI. PNG/JPEG/TIFF and image-only PDF pages
use the supplied OCR engine. Word extraction includes text/tables but does not
OCR embedded images or invent Word page numbers. No macros or document actions
are executed. PDF text ordering, complex tables and OCR output need inspection.

AUTO OCR uses the configured minimum-text threshold per PDF page. OFF never
pretends an image contains text. ALWAYS renders PDF pages before OCR. The
application owns the executable and installed language allowlist; documents do
not supply command flags. Only materialized trusted files are accepted.

Byte, page, character, image-pixel and archive-expansion bounds protect parser
resources. OCR is a subprocess with an enforced deadline and descendant cleanup.
The Java parser deadline is cooperative between parser operations, not a claim
that arbitrary library code can be forcibly interrupted. Run untrusted parsing
inside a resource-limited serving container or an isolated worker; this library
does not resize hosts or create a sandbox automatically.

Output carries stable section IDs and `documentFormat`, `extractionMethod`, and
PDF/image `pageNumber` metadata. Preserve those through the indexing allowlist,
while the host continues to supply authoritative tenant/source/version fields.
Chunk overlap and minimum lengths are set on `SpringAiDocumentIndexingOptions`.
Overlap never crosses input pages/sections.

The `ai-fabric-rag` module exposes `RagEvaluationProvider`, bounded samples and
numeric outcomes. `SpringAiRagEvaluationAdapter` wraps configured Spring AI
evaluators; the existing `SpringAiRagEvaluationService.asProvider()` reuses its
evaluators through that contract. The application still owns retrieval,
authorization, model selection, funding, deadlines and run persistence. Scores
are judgments, not authorization decisions or guarantees of correctness.

`TesseractQualification` is an explicit real-engine check for a generated image
and scanned PDF. The qualification Dockerfile provides a local test environment;
the normal framework CI also runs it after installing the English OCR model.
