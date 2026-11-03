package io.modivra.conversation;

import io.modivra.conversation.ConversationDtos.ConversationDetailResponse;
import io.modivra.conversation.ConversationDtos.ConversationListResponse;
import io.modivra.conversation.ConversationDtos.ConversationPatchRequest;
import io.modivra.conversation.ConversationDtos.ConversationPatchResponse;
import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.GenerateTitleRequest;
import io.modivra.conversation.ConversationDtos.GenerateTitleResponse;
import io.modivra.conversation.ConversationTreeService.TurnPreparationResult;
import java.security.Principal;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
public class ConversationController {

    public static final String DEFAULT_USER_ID = "default-user";
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ConversationService conversationService;
    private final ConversationTreeService treeService;

    public ConversationController(
        ConversationService conversationService,
        ConversationTreeService treeService
    ) {
        this.conversationService = conversationService;
        this.treeService = treeService;
    }

    @PostMapping(
        value = "/conversation",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamConversation(
        @RequestBody ConversationTurnRequest request,
        Principal principal
    ) {
        String userId = resolveUserId(principal);
        // Phase 1 executes in a short REQUIRES_NEW transaction (~3-5ms) and releases the DB connection immediately.
        // Any validation or 409 Conflict error is thrown before committing 200 OK text/event-stream headers.
        TurnPreparationResult prep = treeService.prepareTurn(request, userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        Thread.ofVirtual().name("conv-sse-", 0).start(() -> {
            conversationService.streamPreparedTurn(prep, emitter);
        });
        return emitter;
    }

    @GetMapping(value = "/conversation/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationDetailResponse getConversation(
        @PathVariable("id") UUID id,
        Principal principal
    ) {
        return treeService.getConversationDetail(id, resolveUserId(principal));
    }

    @PatchMapping(
        value = "/conversation/{id}",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ConversationPatchResponse patchConversation(
        @PathVariable("id") UUID id,
        @RequestBody ConversationPatchRequest request,
        Principal principal
    ) {
        return treeService.patchConversation(id, resolveUserId(principal), request);
    }

    @PostMapping(
        value = "/conversation/gen_title/{id}",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public GenerateTitleResponse generateTitle(
        @PathVariable("id") UUID id,
        @RequestBody(required = false) GenerateTitleRequest request,
        Principal principal
    ) {
        return conversationService.generateTitleForConversation(id, resolveUserId(principal), request);
    }

    @GetMapping(value = "/conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ConversationListResponse listConversations(
        @RequestParam(name = "offset", defaultValue = "0") int offset,
        @RequestParam(name = "limit", defaultValue = "28") int limit,
        @RequestParam(name = "is_archived", required = false) Boolean isArchived,
        Principal principal
    ) {
        return treeService.listConversations(resolveUserId(principal), offset, limit, isArchived);
    }

    private static String resolveUserId(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        return DEFAULT_USER_ID;
    }
}
