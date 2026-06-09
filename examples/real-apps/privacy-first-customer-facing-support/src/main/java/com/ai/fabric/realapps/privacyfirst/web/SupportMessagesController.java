package com.ai.fabric.realapps.privacyfirst.web;

import com.ai.fabric.realapps.privacyfirst.domain.SupportMessage;
import com.ai.fabric.realapps.privacyfirst.service.SupportMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/support/messages")
@RequiredArgsConstructor
public class SupportMessagesController {

    private final SupportMessageService supportMessageService;

    @PostMapping
    public SupportMessageResponse create(@Valid @RequestBody CreateSupportMessageRequest request) {
        SupportMessage stored = supportMessageService.create(
            request.customerId(),
            request.channel(),
            request.subject(),
            request.message()
        );
        return SupportMessageResponse.from(stored);
    }

    @GetMapping
    public List<SupportMessageSummaryResponse> list() {
        return supportMessageService.list().stream()
            .map(SupportMessageSummaryResponse::from)
            .toList();
    }

    @GetMapping("/{id}")
    public SupportMessageResponse get(@PathVariable long id) {
        return SupportMessageResponse.from(supportMessageService.get(id));
    }

    public record CreateSupportMessageRequest(
        @NotBlank String customerId,
        @NotBlank String channel,
        String subject,
        @NotBlank String message
    ) {}

    public record SupportMessageSummaryResponse(
        long id,
        String customerId,
        String channel,
        boolean piiDetected,
        String processedSubject,
        String processedMessagePreview,
        Instant createdAt
    ) {
        static SupportMessageSummaryResponse from(SupportMessage message) {
            String preview = message.getProcessedMessage();
            if (preview != null && preview.length() > 160) {
                preview = preview.substring(0, 160) + "...";
            }
            return new SupportMessageSummaryResponse(
                message.getId(),
                message.getCustomerId(),
                message.getChannel(),
                message.isPiiDetected(),
                message.getProcessedSubject(),
                preview,
                message.getCreatedAt()
            );
        }
    }

    public record SupportMessageResponse(
        long id,
        String customerId,
        String channel,
        boolean piiDetected,
        String modeApplied,
        int detectionsCount,
        String detectionsSummary,
        String processedSubject,
        String processedMessage,
        String subjectEncryptedOriginal,
        String subjectEncryptionSalt,
        String messageEncryptedOriginal,
        String messageEncryptionSalt,
        Instant createdAt
    ) {
        static SupportMessageResponse from(SupportMessage message) {
            return new SupportMessageResponse(
                message.getId(),
                message.getCustomerId(),
                message.getChannel(),
                message.isPiiDetected(),
                message.getModeApplied(),
                message.getDetectionsCount(),
                message.getDetectionsSummary(),
                message.getProcessedSubject(),
                message.getProcessedMessage(),
                message.getSubjectEncryptedOriginal(),
                message.getSubjectEncryptionSalt(),
                message.getMessageEncryptedOriginal(),
                message.getMessageEncryptionSalt(),
                message.getCreatedAt()
            );
        }
    }
}
