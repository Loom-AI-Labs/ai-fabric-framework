package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/document-demo")
public class DocumentDemoController {

    private static final Pattern SESSION_ID = Pattern.compile(
        "^docs-demo-[a-f0-9]{32}$"
    );
    private static final String DEFAULT_VISIBILITY = "internal";

    private final DocumentIngestionService documents;

    public DocumentDemoController(DocumentIngestionService documents) {
        this.documents = documents;
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionView createSession() {
        String sessionId = "docs-demo-"
            + UUID.randomUUID().toString().replace("-", "");
        String tenantId = tenantId(sessionId);
        seed(tenantId);
        return session(sessionId);
    }

    @GetMapping("/sessions/{sessionId}")
    public SessionView session(@PathVariable String sessionId) {
        String tenantId = tenantId(sessionId);
        return new SessionView(
            sessionId,
            tenantId,
            documents.listSources(tenantId)
        );
    }

    @PostMapping("/sessions/{sessionId}/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentIngestionService.SourceSummary createSource(
        @PathVariable String sessionId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "visibility", required = false)
        String visibility
    ) {
        return documents.createSource(command(
            file,
            title,
            tenantId(sessionId),
            visibility
        ));
    }

    @PutMapping("/sessions/{sessionId}/sources/{sourceId}/content")
    public DocumentIngestionService.SourceSummary replaceSource(
        @PathVariable String sessionId,
        @PathVariable String sourceId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "visibility", required = false)
        String visibility
    ) {
        String tenantId = tenantId(sessionId);
        requireOwned(sourceId, tenantId);
        return documents.replaceSource(
            sourceId,
            command(file, title, tenantId, visibility)
        );
    }

    @GetMapping("/sessions/{sessionId}/sources/{sourceId}/preview")
    public DocumentIngestionService.PreviewResult preview(
        @PathVariable String sessionId,
        @PathVariable String sourceId
    ) {
        requireOwned(sourceId, tenantId(sessionId));
        return documents.preview(sourceId);
    }

    @GetMapping("/sessions/{sessionId}/sources/{sourceId}")
    public DocumentIngestionService.LifecycleResult status(
        @PathVariable String sessionId,
        @PathVariable String sourceId
    ) {
        requireOwned(sourceId, tenantId(sessionId));
        return documents.status(sourceId);
    }

    @PostMapping("/sessions/{sessionId}/sources/{sourceId}/index")
    public DocumentIngestionService.IndexResult index(
        @PathVariable String sessionId,
        @PathVariable String sourceId
    ) {
        requireOwned(sourceId, tenantId(sessionId));
        return documents.index(sourceId);
    }

    @DeleteMapping("/sessions/{sessionId}/sources/{sourceId}")
    public DocumentIngestionService.DeleteResult delete(
        @PathVariable String sessionId,
        @PathVariable String sourceId
    ) {
        requireOwned(sourceId, tenantId(sessionId));
        return documents.delete(sourceId);
    }

    @GetMapping("/sessions/{sessionId}/query")
    public DocumentIngestionService.QueryResult query(
        @PathVariable String sessionId,
        @RequestParam String query,
        @RequestParam(defaultValue = "5") int limit
    ) {
        return documents.query(query, tenantId(sessionId), limit);
    }

    private void seed(String tenantId) {
        createAndIndex(
            "Returns and Opened Products Policy",
            "returns-policy.txt",
            "Opened laptops may be returned within 14 days of delivery when all accessories are included. "
                + "A 10 percent restocking fee applies only when packaging or accessories are missing. "
                + "Clearance products and gift cards are not eligible for return.",
            tenantId
        );
        createAndIndex(
            "Checkout Recovery Runbook",
            "checkout-runbook.txt",
            "If checkout latency rises after a configuration rollout, compare the deployment revision, "
                + "inspect payment timeouts, and drain unhealthy instances before a restart. "
                + "Escalate to the commerce on-call team when p95 latency remains above two seconds for ten minutes.",
            tenantId
        );
        createAndIndex(
            "Service Ownership Directory",
            "service-owners.json",
            "{\"checkout\":{\"owner\":\"Commerce Platform\",\"channel\":\"#commerce-on-call\"},"
                + "\"payments\":{\"owner\":\"Payments Reliability\",\"channel\":\"#payments-ops\"}}",
            tenantId
        );
    }

    private void createAndIndex(
        String title,
        String filename,
        String content,
        String tenantId
    ) {
        String contentType = filename.endsWith(".json")
            ? "application/json"
            : "text/plain";
        var source = documents.createSource(
            new DocumentIngestionService.CreateSourceCommand(
                title,
                filename,
                contentType,
                tenantId,
                DEFAULT_VISIBILITY,
                content.getBytes(StandardCharsets.UTF_8)
            )
        );
        documents.index(source.id());
    }

    private void requireOwned(String sourceId, String tenantId) {
        var source = documents.status(sourceId).source();
        if (!tenantId.equals(source.tenantId())) {
            throw new IllegalArgumentException(
                "Document source does not belong to this demo session"
            );
        }
    }

    private String tenantId(String sessionId) {
        if (sessionId == null || !SESSION_ID.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid demo session identifier");
        }
        return "tenant-" + sessionId;
    }

    private DocumentIngestionService.CreateSourceCommand command(
        MultipartFile file,
        String title,
        String tenantId,
        String visibility
    ) {
        try {
            return new DocumentIngestionService.CreateSourceCommand(
                title,
                file.getOriginalFilename(),
                file.getContentType(),
                tenantId,
                visibility == null || visibility.isBlank()
                    ? DEFAULT_VISIBILITY
                    : visibility,
                file.getBytes()
            );
        } catch (IOException exception) {
            throw new IllegalArgumentException(
                "Unable to read uploaded document",
                exception
            );
        }
    }

    public record SessionView(
        String sessionId,
        String tenantId,
        List<DocumentIngestionService.SourceSummary> sources
    ) {
        public SessionView {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }
}
