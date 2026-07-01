package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/documents/sources")
@RequiredArgsConstructor
public class DocumentIngestionController {

    private final DocumentIngestionService documentIngestionService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentIngestionService.SourceSummary createSource(
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "tenantId", required = false) String tenantId,
        @RequestParam(value = "visibility", required = false) String visibility
    ) {
        return documentIngestionService.createSource(command(file, title, tenantId, visibility));
    }

    @PutMapping("/{sourceId}/content")
    public DocumentIngestionService.SourceSummary replaceSource(
        @PathVariable String sourceId,
        @RequestParam("file") MultipartFile file,
        @RequestParam(value = "title", required = false) String title,
        @RequestParam(value = "tenantId", required = false) String tenantId,
        @RequestParam(value = "visibility", required = false) String visibility
    ) {
        return documentIngestionService.replaceSource(sourceId, command(file, title, tenantId, visibility));
    }

    @GetMapping("/{sourceId}/preview")
    public DocumentIngestionService.PreviewResult preview(@PathVariable String sourceId) {
        return documentIngestionService.preview(sourceId);
    }

    @PostMapping("/{sourceId}/index")
    public DocumentIngestionService.IndexResult index(@PathVariable String sourceId) {
        return documentIngestionService.index(sourceId);
    }

    @DeleteMapping("/{sourceId}")
    public DocumentIngestionService.DeleteResult delete(@PathVariable String sourceId) {
        return documentIngestionService.delete(sourceId);
    }

    private DocumentIngestionService.CreateSourceCommand command(MultipartFile file,
                                                                 String title,
                                                                 String tenantId,
                                                                 String visibility) {
        try {
            return new DocumentIngestionService.CreateSourceCommand(
                title,
                file.getOriginalFilename(),
                file.getContentType(),
                tenantId,
                visibility,
                file.getBytes()
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded document", ex);
        }
    }
}
