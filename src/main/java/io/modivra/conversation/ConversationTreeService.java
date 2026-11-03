package io.modivra.conversation;

import io.modivra.conversation.ConversationDtos.AuthorDto;
import io.modivra.conversation.ConversationDtos.ContentDto;
import io.modivra.conversation.ConversationDtos.ConversationDetailResponse;
import io.modivra.conversation.ConversationDtos.ConversationListResponse;
import io.modivra.conversation.ConversationDtos.ConversationPatchRequest;
import io.modivra.conversation.ConversationDtos.ConversationPatchResponse;
import io.modivra.conversation.ConversationDtos.ConversationSummaryDto;
import io.modivra.conversation.ConversationDtos.ConversationTurnRequest;
import io.modivra.conversation.ConversationDtos.MessageDto;
import io.modivra.conversation.ConversationDtos.NodeMappingDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ConversationTreeService {

    public static final Duration STALE_TURN_TIMEOUT = Duration.ofSeconds(180);

    private final ConversationRepository conversationRepository;
    private final ConversationNodeRepository nodeRepository;
    private final String defaultModelSlug;

    public ConversationTreeService(
        ConversationRepository conversationRepository,
        ConversationNodeRepository nodeRepository,
        @Value("${spring.ai.openai.chat.options.model:grok-4.7}") String defaultModelSlug
    ) {
        this.conversationRepository = conversationRepository;
        this.nodeRepository = nodeRepository;
        this.defaultModelSlug = defaultModelSlug;
    }

    public record TurnPreparationResult(
        UUID conversationId,
        UUID userNodeId,
        UUID assistantNodeId,
        String modelSlug,
        String defaultModelSlug,
        String action,
        Instant assistantCreatedAt,
        List<Message> promptMessages,
        String firstUserPromptText,
        boolean firstTurn,
        MessageDto initialAssistantMessage
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TurnPreparationResult prepareTurn(ConversationTurnRequest request, String userId) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }

        String action = (request.action() != null && !request.action().isBlank())
            ? request.action().trim()
            : "next";
        if (!"next".equals(action) && !"variant".equals(action)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported action: " + action);
        }

        String userPromptText = null;
        UUID requestedUserMessageId = null;
        if ("next".equals(action)) {
            if (request.messages() == null || request.messages().isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "messages is required when action is 'next'"
                );
            }
            MessageDto lastMsg = request.messages().getLast();
            if (lastMsg == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message item cannot be null"
                );
            }
            if (lastMsg.author() != null
                && lastMsg.author().role() != null
                && !"user".equals(lastMsg.author().role())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Message author role must be 'user' when action is 'next'"
                );
            }
            userPromptText = lastMsg.content() != null ? lastMsg.content().extractText() : "";
            if (userPromptText.isBlank()) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User message content cannot be blank when action is 'next'"
                );
            }
            if (lastMsg.id() != null && !lastMsg.id().isBlank()) {
                try {
                    requestedUserMessageId = UUID.fromString(lastMsg.id());
                } catch (IllegalArgumentException ignored) {
                    requestedUserMessageId = null;
                }
            }
        } else {
            // action == "variant"
            if (request.conversationId() == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "conversation_id is required when action is 'variant'"
                );
            }
        }

        String resolvedModel = (request.model() != null && !request.model().isBlank())
            ? request.model().trim()
            : this.defaultModelSlug;

        Instant now = Instant.now();
        ConversationEntity conversation;
        Map<UUID, ConversationNodeEntity> nodeMap = new LinkedHashMap<>();
        ConversationNodeEntity parentForTurn;
        boolean firstTurn = false;

        if (request.conversationId() == null) {
            firstTurn = true;
            conversation = new ConversationEntity();
            conversation.setId(UUID.randomUUID());
            conversation.setUserId(userId);
            conversation.setTitle("New chat");
            conversation.setDefaultModelSlug(resolvedModel);
            conversation.setArchived(false);
            conversation.setVisible(true);
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversationRepository.save(conversation);

            // 1. Hidden Root Node: parent = null, role = null, content = null
            ConversationNodeEntity rootNode = new ConversationNodeEntity();
            rootNode.setId(UUID.randomUUID());
            rootNode.setConversationId(conversation.getId());
            rootNode.setParent(null);
            rootNode.setRole(null);
            rootNode.setContent(null);
            rootNode.setModelSlug(null);
            rootNode.setStatus("finished_successfully");
            rootNode.setMetadata(Map.of("is_visually_hidden_from_conversation", true));
            rootNode.setCreatedAt(now);
            rootNode.setUpdatedAt(now);
            nodeRepository.save(rootNode);
            nodeMap.put(rootNode.getId(), rootNode);

            // 2. Hidden System Node: role = "system", is_visually_hidden_from_conversation = true
            Instant sysTime = now.plusNanos(1_000);
            ConversationNodeEntity systemNode = new ConversationNodeEntity();
            systemNode.setId(UUID.randomUUID());
            systemNode.setConversationId(conversation.getId());
            systemNode.setParent(rootNode);
            systemNode.setRole("system");
            systemNode.setContent(ContentDto.text(""));
            systemNode.setModelSlug(null);
            systemNode.setStatus("finished_successfully");
            systemNode.setMetadata(Map.of("is_visually_hidden_from_conversation", true));
            systemNode.setCreatedAt(sysTime);
            systemNode.setUpdatedAt(sysTime);
            nodeRepository.save(systemNode);
            nodeMap.put(systemNode.getId(), systemNode);

            parentForTurn = systemNode;
        } else {
            conversation = conversationRepository.findByIdForUpdate(request.conversationId(), userId)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Conversation not found: " + request.conversationId()
                ));

            List<ConversationNodeEntity> inProgressNodes = nodeRepository
                .findByConversationIdAndStatus(conversation.getId(), "in_progress");
            Instant staleCutoff = now.minus(STALE_TURN_TIMEOUT);
            for (ConversationNodeEntity inProgressNode : inProgressNodes) {
                Instant nodeTime = inProgressNode.getUpdatedAt() != null
                    ? inProgressNode.getUpdatedAt()
                    : inProgressNode.getCreatedAt();
                if (nodeTime != null && nodeTime.isBefore(staleCutoff)) {
                    inProgressNode.setStatus("interrupted");
                    Map<String, Object> staleMeta = new LinkedHashMap<>(inProgressNode.getMetadata());
                    staleMeta.put("finish_details", Map.of("type", "interrupted"));
                    staleMeta.put("end_turn", false);
                    inProgressNode.setMetadata(staleMeta);
                    inProgressNode.setUpdatedAt(now);
                    nodeRepository.saveAndFlush(inProgressNode);
                } else {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Conversation already has a turn in progress"
                    );
                }
            }

            List<ConversationNodeEntity> existingNodes = nodeRepository
                .findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
            if (!existingNodes.isEmpty()) {
                Instant lastNodeTime = existingNodes.getLast().getCreatedAt();
                if (lastNodeTime != null && !now.isAfter(lastNodeTime)) {
                    now = lastNodeTime.plusNanos(1_000);
                }
            }
            for (ConversationNodeEntity n : existingNodes) {
                nodeMap.put(n.getId(), n);
            }

            if (request.parentMessageId() != null) {
                parentForTurn = nodeMap.get(request.parentMessageId());
                if (parentForTurn == null) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "parent_message_id does not belong to conversation: " + request.parentMessageId()
                    );
                }
            } else if (conversation.getCurrentNode() != null) {
                parentForTurn = nodeMap.get(conversation.getCurrentNode());
            } else {
                parentForTurn = existingNodes.isEmpty() ? null : existingNodes.getLast();
            }

            if (parentForTurn == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unable to resolve parent node for conversation turn"
                );
            }

            // Prevent attaching user node directly to Hidden Root Node (bypassing Hidden System Node)
            if (parentForTurn.getParentId() == null && parentForTurn.getRole() == null) {
                UUID rootId = parentForTurn.getId();
                ConversationNodeEntity systemChild = existingNodes.stream()
                    .filter(n -> rootId.equals(n.getParentId()) && "system".equals(n.getRole()))
                    .findFirst()
                    .orElse(null);
                if (systemChild != null) {
                    parentForTurn = systemChild;
                }
            }
        }

        ConversationNodeEntity userNode;
        if ("next".equals(action)) {
            Instant userTime = now.plusNanos(2_000);
            UUID userNodeId = (requestedUserMessageId != null
                && !nodeMap.containsKey(requestedUserMessageId)
                && !nodeRepository.existsById(requestedUserMessageId))
                ? requestedUserMessageId
                : UUID.randomUUID();

            userNode = new ConversationNodeEntity();
            userNode.setId(userNodeId);
            userNode.setConversationId(conversation.getId());
            userNode.setParent(parentForTurn);
            userNode.setRole("user");
            userNode.setContent(ContentDto.text(userPromptText));
            userNode.setModelSlug(null);
            userNode.setStatus("finished_successfully");
            userNode.setMetadata(Map.of("message_type", "next"));
            userNode.setCreatedAt(userTime);
            userNode.setUpdatedAt(userTime);
            nodeRepository.save(userNode);
            nodeMap.put(userNode.getId(), userNode);
        } else {
            // action == "variant": attach new assistant node directly to the user node
            if ("user".equals(parentForTurn.getRole())) {
                userNode = parentForTurn;
            } else if ("assistant".equals(parentForTurn.getRole()) && parentForTurn.getParentId() != null) {
                userNode = nodeMap.get(parentForTurn.getParentId());
            } else {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Variant regeneration must target a user node or an assistant node with a user parent"
                );
            }
            if (userNode == null || !"user".equals(userNode.getRole())) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Target parent for variant must resolve to a user node"
                );
            }
            userPromptText = userNode.getContent() != null ? userNode.getContent().extractText() : "";
        }

        Instant assistantTime = now.plusNanos(3_000);
        ConversationNodeEntity assistantNode = new ConversationNodeEntity();
        assistantNode.setId(UUID.randomUUID());
        assistantNode.setConversationId(conversation.getId());
        assistantNode.setParent(userNode);
        assistantNode.setRole("assistant");
        assistantNode.setContent(ContentDto.text(""));
        assistantNode.setModelSlug(resolvedModel);
        assistantNode.setStatus("in_progress");

        Map<String, Object> assistantMeta = new LinkedHashMap<>();
        assistantMeta.put("message_type", action);
        assistantMeta.put("model_slug", resolvedModel);
        assistantMeta.put("default_model_slug", conversation.getDefaultModelSlug());
        assistantNode.setMetadata(assistantMeta);
        assistantNode.setCreatedAt(assistantTime);
        assistantNode.setUpdatedAt(assistantTime);
        nodeRepository.save(assistantNode);
        nodeMap.put(assistantNode.getId(), assistantNode);

        conversation.setCurrentNode(assistantNode.getId());
        conversation.setUpdatedAt(assistantTime);
        conversationRepository.saveAndFlush(conversation);

        List<Message> promptMessages = resolveBranchMessages(userNode, nodeMap);

        MessageDto initialAssistantMessage = new MessageDto(
            assistantNode.getId().toString(),
            AuthorDto.ofRole("assistant"),
            ConversationDtos.toEpochSeconds(assistantTime),
            null,
            ContentDto.text(""),
            "in_progress",
            null,
            1.0,
            assistantMeta,
            "all"
        );

        return new TurnPreparationResult(
            conversation.getId(),
            userNode.getId(),
            assistantNode.getId(),
            resolvedModel,
            conversation.getDefaultModelSlug(),
            action,
            assistantTime,
            promptMessages,
            userPromptText,
            firstTurn,
            initialAssistantMessage
        );
    }

    private List<Message> resolveBranchMessages(
        ConversationNodeEntity leafUserNode,
        Map<UUID, ConversationNodeEntity> nodeMap
    ) {
        List<ConversationNodeEntity> branchNodes = new ArrayList<>();
        ConversationNodeEntity cursor = leafUserNode;
        while (cursor != null) {
            branchNodes.add(cursor);
            UUID parentId = cursor.getParentId();
            cursor = parentId != null ? nodeMap.get(parentId) : null;
        }
        Collections.reverse(branchNodes);

        List<Message> messages = new ArrayList<>();
        for (ConversationNodeEntity node : branchNodes) {
            String role = node.getRole();
            if (role == null) {
                // Skip Root Node
                continue;
            }
            String text = node.getContent() != null ? node.getContent().extractText() : "";
            switch (role) {
                case "system" -> {
                    // Filter out empty system messages to prevent HTTP 400 from xAI
                    if (!text.isBlank()) {
                        messages.add(new SystemMessage(text));
                    }
                }
                case "user" -> messages.add(new UserMessage(text));
                case "assistant" -> {
                    // Skip empty assistant messages (e.g. from earlier interrupted/failed turns with 0 tokens)
                    if (!text.isBlank()) {
                        messages.add(new AssistantMessage(text));
                    }
                }
                default -> {
                    if (!text.isBlank()) {
                        messages.add(new UserMessage(text));
                    }
                }
            }
        }
        return List.copyOf(messages);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeAssistantTurn(
        UUID conversationId,
        UUID assistantNodeId,
        UUID expectedParentNodeId,
        String fullContent,
        Map<String, Object> usageMetadata
    ) {
        Instant now = Instant.now();
        ConversationNodeEntity assistantNode = nodeRepository.findById(assistantNodeId)
            .orElseThrow(() -> new IllegalStateException("Assistant node not found: " + assistantNodeId));

        assistantNode.setContent(ContentDto.text(fullContent));
        assistantNode.setStatus("finished_successfully");
        Map<String, Object> meta = new LinkedHashMap<>(assistantNode.getMetadata());
        meta.put("finish_details", Map.of("type", "stop"));
        meta.put("end_turn", true);
        if (usageMetadata != null && !usageMetadata.isEmpty()) {
            meta.put("usage", usageMetadata);
        }
        assistantNode.setMetadata(meta);
        assistantNode.setUpdatedAt(now);
        nodeRepository.saveAndFlush(assistantNode);

        // Conditional atomic update: only update conversation if current_node was not switched away via PATCH
        conversationRepository.updateCurrentNodeIfOnActiveBranch(
            conversationId,
            assistantNodeId,
            now
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void interruptAssistantTurn(
        UUID conversationId,
        UUID assistantNodeId,
        String partialContent
    ) {
        Instant now = Instant.now();
        nodeRepository.findById(assistantNodeId).ifPresent(assistantNode -> {
            assistantNode.setContent(ContentDto.text(partialContent != null ? partialContent : ""));
            assistantNode.setStatus("interrupted");
            Map<String, Object> meta = new LinkedHashMap<>(assistantNode.getMetadata());
            meta.put("finish_details", Map.of("type", "interrupted"));
            meta.put("end_turn", false);
            assistantNode.setMetadata(meta);
            assistantNode.setUpdatedAt(now);
            nodeRepository.saveAndFlush(assistantNode);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failAssistantTurn(
        UUID conversationId,
        UUID assistantNodeId,
        String partialContent,
        String errorReason
    ) {
        Instant now = Instant.now();
        nodeRepository.findById(assistantNodeId).ifPresent(assistantNode -> {
            assistantNode.setContent(ContentDto.text(partialContent != null ? partialContent : ""));
            assistantNode.setStatus("failed");
            Map<String, Object> meta = new LinkedHashMap<>(assistantNode.getMetadata());
            meta.put("error", errorReason != null ? errorReason : "Unknown upstream error");
            meta.put("end_turn", false);
            assistantNode.setMetadata(meta);
            assistantNode.setUpdatedAt(now);
            nodeRepository.saveAndFlush(assistantNode);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateTitle(UUID conversationId, String title) {
        if (title == null || title.isBlank()) {
            return;
        }
        conversationRepository.updateConversationTitle(conversationId, title.trim(), Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String updateTitleIfDefault(UUID conversationId, String generatedTitle) {
        String candidate = (generatedTitle != null && !generatedTitle.isBlank())
            ? generatedTitle.trim()
            : "New chat";
        int updated = conversationRepository.updateConversationTitleIfDefault(
            conversationId,
            candidate,
            Instant.now()
        );
        if (updated > 0) {
            return candidate;
        }
        return conversationRepository.findById(conversationId)
            .map(ConversationEntity::getTitle)
            .orElse(candidate);
    }

    @Transactional(readOnly = true)
    public ConversationDetailResponse getConversationDetail(UUID conversationId, String userId) {
        ConversationEntity conversation = conversationRepository
            .findByIdAndUserIdAndVisibleTrue(conversationId, userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId
            ));

        Map<String, NodeMappingDto> mapping = assembleTreeMapping(conversationId);

        return new ConversationDetailResponse(
            conversation.getId(),
            conversation.getTitle(),
            ConversationDtos.toEpochSeconds(conversation.getCreatedAt()),
            ConversationDtos.toEpochSeconds(conversation.getUpdatedAt()),
            mapping,
            conversation.getCurrentNode(),
            conversation.getDefaultModelSlug(),
            conversation.isArchived(),
            conversation.isVisible()
        );
    }

    @Transactional(readOnly = true)
    public Map<String, NodeMappingDto> assembleTreeMapping(UUID conversationId) {
        List<ConversationNodeEntity> nodes = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conversationId);

        Map<UUID, List<String>> childrenByParent = new LinkedHashMap<>();
        for (ConversationNodeEntity node : nodes) {
            childrenByParent.put(node.getId(), new ArrayList<>());
        }
        for (ConversationNodeEntity node : nodes) {
            UUID parentId = node.getParentId();
            if (parentId != null) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>())
                    .add(node.getId().toString());
            }
        }

        Map<String, NodeMappingDto> mapping = new LinkedHashMap<>();
        for (ConversationNodeEntity node : nodes) {
            String nodeIdStr = node.getId().toString();
            String parentIdStr = node.getParentId() != null ? node.getParentId().toString() : null;
            List<String> children = childrenByParent.getOrDefault(node.getId(), List.of());
            MessageDto messageDto = toMessageDto(node);
            mapping.put(nodeIdStr, new NodeMappingDto(nodeIdStr, messageDto, parentIdStr, children));
        }
        return mapping;
    }

    private MessageDto toMessageDto(ConversationNodeEntity node) {
        if (node.getParentId() == null && node.getRole() == null) {
            // Hidden Root Node has message = null
            return null;
        }
        Boolean endTurn = null;
        if ("finished_successfully".equals(node.getStatus())) {
            endTurn = Boolean.TRUE;
        } else if (node.getMetadata() != null && node.getMetadata().get("end_turn") instanceof Boolean b) {
            endTurn = b;
        }

        return new MessageDto(
            node.getId().toString(),
            AuthorDto.ofRole(node.getRole()),
            ConversationDtos.toEpochSeconds(node.getCreatedAt()),
            ConversationDtos.toEpochSeconds(node.getUpdatedAt()),
            node.getContent() != null ? node.getContent() : ContentDto.text(""),
            node.getStatus(),
            endTurn,
            1.0,
            node.getMetadata(),
            "all"
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ConversationPatchResponse patchConversation(
        UUID conversationId,
        String userId,
        ConversationPatchRequest request
    ) {
        ConversationEntity conversation = conversationRepository.findByIdAndUserId(conversationId, userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId
            ));

        if (!conversation.isVisible() && (request == null || !Boolean.TRUE.equals(request.isVisible()))) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId
            );
        }

        if (request != null) {
            if (request.title() != null) {
                if (request.title().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title cannot be blank");
                }
                conversation.setTitle(request.title().trim());
            }
            if (request.isArchived() != null) {
                conversation.setArchived(request.isArchived());
            }
            if (request.isVisible() != null) {
                conversation.setVisible(request.isVisible());
            }
            if (request.currentNode() != null) {
                boolean nodeExists = nodeRepository.existsByIdAndConversationId(
                    request.currentNode(),
                    conversationId
                );
                if (!nodeExists) {
                    throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "current_node does not belong to conversation: " + request.currentNode()
                    );
                }
                conversation.setCurrentNode(request.currentNode());
            }
        }

        conversation.setUpdatedAt(Instant.now());
        ConversationEntity saved = conversationRepository.saveAndFlush(conversation);

        return new ConversationPatchResponse(
            true,
            saved.getId(),
            saved.getTitle(),
            saved.isArchived(),
            saved.isVisible(),
            saved.getCurrentNode()
        );
    }

    @Transactional(readOnly = true)
    public ConversationListResponse listConversations(
        String userId,
        int offset,
        int limit,
        Boolean isArchived
    ) {
        int safeLimit = Math.clamp(limit, 1, 100);
        int safeOffset = Math.max(0, offset);
        boolean targetArchived = isArchived != null && isArchived;

        org.springframework.data.domain.Pageable pageable = new OffsetPageable(safeOffset, safeLimit);
        Page<ConversationEntity> page = conversationRepository
            .findByUserIdAndVisibleTrueAndArchivedOrderByUpdatedAtDesc(
                userId,
                targetArchived,
                pageable
            );
        long total = conversationRepository.countByUserIdAndVisibleTrueAndArchived(userId, targetArchived);

        List<ConversationSummaryDto> items = page.getContent().stream()
            .map(c -> new ConversationSummaryDto(
                c.getId(),
                c.getId(),
                c.getTitle(),
                ConversationDtos.toEpochSeconds(c.getCreatedAt()),
                ConversationDtos.toEpochSeconds(c.getUpdatedAt()),
                c.getCurrentNode(),
                c.getDefaultModelSlug(),
                c.isArchived()
            ))
            .toList();

        return new ConversationListResponse(
            items,
            total,
            safeLimit,
            safeOffset,
            false
        );
    }

    private record OffsetPageable(int offsetVal, int limitVal)
        implements org.springframework.data.domain.Pageable {

        @Override
        public int getPageNumber() {
            return offsetVal / limitVal;
        }

        @Override
        public int getPageSize() {
            return limitVal;
        }

        @Override
        public long getOffset() {
            return offsetVal;
        }

        @Override
        public org.springframework.data.domain.Sort getSort() {
            return org.springframework.data.domain.Sort.unsorted();
        }

        @Override
        public org.springframework.data.domain.Pageable next() {
            return new OffsetPageable(offsetVal + limitVal, limitVal);
        }

        @Override
        public org.springframework.data.domain.Pageable previousOrFirst() {
            return hasPrevious() ? new OffsetPageable(Math.max(0, offsetVal - limitVal), limitVal) : first();
        }

        @Override
        public org.springframework.data.domain.Pageable first() {
            return new OffsetPageable(0, limitVal);
        }

        @Override
        public org.springframework.data.domain.Pageable withPage(int pageNumber) {
            return new OffsetPageable(pageNumber * limitVal, limitVal);
        }

        @Override
        public boolean hasPrevious() {
            return offsetVal > 0;
        }
    }

    @Transactional(readOnly = true)
    public String resolvePromptForTitleGeneration(UUID conversationId, String userId, UUID messageId) {
        ConversationEntity conversation = conversationRepository
            .findByIdAndUserIdAndVisibleTrue(conversationId, userId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Conversation not found: " + conversationId
            ));

        if (messageId != null) {
            ConversationNodeEntity node = nodeRepository.findByIdAndConversationId(messageId, conversation.getId())
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "message_id does not belong to conversation: " + messageId
                ));
            String targetedText = node.getContent() != null ? node.getContent().extractText() : "";
            if (!targetedText.isBlank()) {
                return targetedText;
            }
        }

        List<ConversationNodeEntity> nodes = nodeRepository
            .findByConversationIdOrderByCreatedAtAscIdAsc(conversation.getId());
        for (ConversationNodeEntity node : nodes) {
            if ("user".equals(node.getRole()) && node.getContent() != null) {
                String text = node.getContent().extractText();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return conversation.getTitle();
    }
}
