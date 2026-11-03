package io.modivra.conversation;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ConversationDtos {

    private ConversationDtos() {
    }

    public static Double toEpochSeconds(Instant instant) {
        if (instant == null) {
            return null;
        }
        return instant.getEpochSecond() + (instant.getNano() / 1_000_000_000.0);
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AuthorDto(
        @JsonProperty("role") String role,
        @JsonProperty("name") String name,
        @JsonProperty("metadata") Map<String, Object> metadata
    ) {
        public AuthorDto {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        public static AuthorDto ofRole(String role) {
            return new AuthorDto(role, null, Map.of());
        }
    }

    public record ContentDto(
        @JsonProperty("content_type") String contentType,
        @JsonProperty("parts") List<String> parts
    ) {
        public ContentDto {
            contentType = (contentType != null && !contentType.isBlank()) ? contentType : "text";
            parts = parts != null ? List.copyOf(parts) : List.of("");
        }

        public static ContentDto text(String text) {
            return new ContentDto("text", List.of(text != null ? text : ""));
        }

        public String extractText() {
            if (parts == null || parts.isEmpty()) {
                return "";
            }
            return parts.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageDto(
        @JsonProperty("id") String id,
        @JsonProperty("author") AuthorDto author,
        @JsonProperty("create_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double createTime,
        @JsonProperty("update_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double updateTime,
        @JsonProperty("content") ContentDto content,
        @JsonProperty("status") String status,
        @JsonProperty("end_turn") Boolean endTurn,
        @JsonProperty("weight") Double weight,
        @JsonProperty("metadata") Map<String, Object> metadata,
        @JsonProperty("recipient") String recipient
    ) {
        public MessageDto {
            metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NodeMappingDto(
        @JsonProperty("id") String id,
        @JsonProperty("message") MessageDto message,
        @JsonProperty("parent") String parent,
        @JsonProperty("children") List<String> children
    ) {
        public NodeMappingDto {
            children = children != null ? List.copyOf(children) : List.of();
        }
    }

    public record ConversationTurnRequest(
        @JsonProperty("action") String action,
        @JsonProperty("conversation_id") UUID conversationId,
        @JsonProperty("parent_message_id") UUID parentMessageId,
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<MessageDto> messages
    ) {
    }

    public record ConversationPatchRequest(
        @JsonProperty("title") String title,
        @JsonProperty("is_archived") Boolean isArchived,
        @JsonProperty("is_visible") Boolean isVisible,
        @JsonProperty("current_node") UUID currentNode
    ) {
    }

    public record ConversationPatchResponse(
        @JsonProperty("success") boolean success,
        @JsonProperty("conversation_id") UUID conversationId,
        @JsonProperty("title") String title,
        @JsonProperty("is_archived") boolean isArchived,
        @JsonProperty("is_visible") boolean isVisible,
        @JsonProperty("current_node") UUID currentNode
    ) {
    }

    public record ConversationDetailResponse(
        @JsonProperty("conversation_id") UUID conversationId,
        @JsonProperty("title") String title,
        @JsonProperty("create_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double createTime,
        @JsonProperty("update_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double updateTime,
        @JsonProperty("mapping") Map<String, NodeMappingDto> mapping,
        @JsonProperty("current_node") UUID currentNode,
        @JsonProperty("default_model_slug") String defaultModelSlug,
        @JsonProperty("is_archived") boolean isArchived,
        @JsonProperty("is_visible") boolean isVisible
    ) {
    }

    public record ConversationSummaryDto(
        @JsonProperty("id") UUID id,
        @JsonProperty("conversation_id") UUID conversationId,
        @JsonProperty("title") String title,
        @JsonProperty("create_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double createTime,
        @JsonProperty("update_time") @JsonFormat(shape = JsonFormat.Shape.NUMBER) Double updateTime,
        @JsonProperty("current_node") UUID currentNode,
        @JsonProperty("default_model_slug") String defaultModelSlug,
        @JsonProperty("is_archived") boolean isArchived
    ) {
    }

    public record ConversationListResponse(
        @JsonProperty("items") List<ConversationSummaryDto> items,
        @JsonProperty("total") long total,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset") int offset,
        @JsonProperty("has_missing_conversations") boolean hasMissingConversations
    ) {
    }

    public record GenerateTitleRequest(
        @JsonProperty("message_id") UUID messageId
    ) {
    }

    public record GenerateTitleResponse(
        @JsonProperty("title") String title,
        @JsonProperty("conversation_id") UUID conversationId
    ) {
    }

    public record SseDeltaPatch(
        @JsonProperty("p") String path,
        @JsonProperty("o") String op,
        @JsonProperty("v") Object value
    ) {
    }

    public record SseInitialStateValue(
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("message") MessageDto message
    ) {
    }

    public record SseTitleGenerationEvent(
        @JsonProperty("type") String type,
        @JsonProperty("title") String title,
        @JsonProperty("conversation_id") String conversationId
    ) {
        public static SseTitleGenerationEvent of(String title, UUID conversationId) {
            return new SseTitleGenerationEvent("title_generation", title, conversationId.toString());
        }
    }

    public record SseStreamCompleteEvent(
        @JsonProperty("type") String type,
        @JsonProperty("conversation_id") String conversationId
    ) {
        public static SseStreamCompleteEvent of(UUID conversationId) {
            return new SseStreamCompleteEvent("message_stream_complete", conversationId.toString());
        }
    }

    public record SseErrorEvent(
        @JsonProperty("type") String type,
        @JsonProperty("message") String message
    ) {
        public static SseErrorEvent of(String message) {
            return new SseErrorEvent("error", message);
        }
    }
}
