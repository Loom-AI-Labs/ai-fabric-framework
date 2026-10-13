package com.ai.fabric.realapps.handover;

import com.ai.fabric.realapps.handover.ConversationHandoverDemoService.ConversationView;
import com.ai.fabric.realapps.handover.ConversationHandoverDemoService.MessageRequest;
import com.ai.fabric.realapps.handover.ConversationHandoverDemoService.QueueItem;
import com.ai.fabric.realapps.handover.ConversationHandoverDemoService.TransitionRequest;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demo")
class ConversationHandoverDemoController {

    private static final String OWNER = "demo.owner";
    private static final String CONVERSATION = "demo.conversation";
    private static final String OPERATOR = "demo.operator";

    private final ConversationHandoverDemoService service;
    private final String operatorAccessCode;

    ConversationHandoverDemoController(
        ConversationHandoverDemoService service,
        @Value("${app.demo.operator-access-code}") String operatorAccessCode
    ) {
        this.service = service;
        this.operatorAccessCode = operatorAccessCode;
    }

    @PostMapping("/session")
    public Map<String, Object> session(HttpSession session) {
        String ownerId = owner(session);
        String conversationId = (String) session.getAttribute(CONVERSATION);
        ConversationView view;
        if (conversationId == null) {
            view = service.create(ownerId);
            conversationId = view.control().scope().conversationId();
            session.setAttribute(CONVERSATION, conversationId);
        } else {
            view = service.ownerView(ownerId, conversationId);
        }
        return Map.of("conversationId", conversationId, "conversation", view);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationView conversation(HttpSession session, @PathVariable String conversationId) {
        return service.ownerView(owner(session), conversationId);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public ConversationView userMessage(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody MessageRequest request
    ) {
        return service.userMessage(owner(session), conversationId, request.text(), request.clientMessageId());
    }

    @PostMapping("/conversations/{conversationId}/handover")
    public ConversationView requestHuman(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody TransitionRequest request
    ) {
        return service.requestHuman(owner(session), conversationId, request);
    }

    @PostMapping("/conversations/{conversationId}/handover/cancel")
    public ConversationView cancelHuman(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody TransitionRequest request
    ) {
        return service.cancelHuman(owner(session), conversationId, request);
    }

    @PostMapping("/operator/session")
    public Map<String, Object> operatorSession(
        HttpSession session,
        @RequestBody OperatorLogin request
    ) {
        byte[] supplied = String.valueOf(request.accessCode()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = operatorAccessCode.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(supplied, expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Operator access code was not accepted.");
        }
        String operatorId = "demo-operator";
        session.setAttribute(OPERATOR, operatorId);
        return Map.of("authenticated", true, "operatorId", operatorId, "displayName", "Demo operator");
    }

    @GetMapping("/operator/conversations")
    public List<QueueItem> operatorQueue(HttpSession session) {
        requireOperator(session);
        return service.queue();
    }

    @GetMapping("/operator/conversations/{conversationId}")
    public ConversationView operatorConversation(
        HttpSession session,
        @PathVariable String conversationId
    ) {
        requireOperator(session);
        return service.operatorView(conversationId);
    }

    @PostMapping("/operator/conversations/{conversationId}/claim")
    public ConversationView claim(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody TransitionRequest request
    ) {
        return service.claim(conversationId, request, requireOperator(session));
    }

    @PostMapping("/operator/conversations/{conversationId}/messages")
    public ConversationView humanMessage(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody MessageRequest request
    ) {
        return service.humanMessage(conversationId, request, requireOperator(session));
    }

    @PostMapping("/operator/conversations/{conversationId}/return-to-ai")
    public ConversationView returnToAi(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody TransitionRequest request
    ) {
        return service.returnToAi(conversationId, request, requireOperator(session));
    }

    @PostMapping("/operator/conversations/{conversationId}/close")
    public ConversationView close(
        HttpSession session,
        @PathVariable String conversationId,
        @RequestBody TransitionRequest request
    ) {
        return service.close(conversationId, request, requireOperator(session));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "conversation-handover-demo",
            "aiFabricVersion", "0.8.32",
            "proof", "Model-selected governed handover action plus AI Fabric conversation control",
            "handoverAction", "request_conversation_handover"
        );
    }

    private String owner(HttpSession session) {
        String owner = (String) session.getAttribute(OWNER);
        if (owner == null) {
            owner = "owner-" + UUID.randomUUID();
            session.setAttribute(OWNER, owner);
        }
        return owner;
    }

    private String requireOperator(HttpSession session) {
        String operator = (String) session.getAttribute(OPERATOR);
        if (operator == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Operator session required.");
        }
        return operator;
    }

    record OperatorLogin(String accessCode) {}
}
