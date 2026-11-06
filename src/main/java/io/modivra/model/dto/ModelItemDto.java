package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.modivra.model.entity.ModelEntity;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ModelItemDto(
    @JsonProperty("id") String id,
    @JsonProperty("object") String object,
    @JsonProperty("created") long created,
    @JsonProperty("owned_by") String ownedBy,
    @JsonProperty("slug") String slug,
    @JsonProperty("provider_id") String providerId,
    @JsonProperty("model_id") String modelId,
    @JsonProperty("category") String category,
    @JsonProperty("is_default") boolean isDefault,
    @JsonProperty("max_tokens") int maxTokens,
    @JsonProperty("context_window") int contextWindow,
    @JsonProperty("max_output_tokens") int maxOutputTokens,
    @JsonProperty("title") String title,
    @JsonProperty("description") String description,
    @JsonProperty("tags") List<String> tags,
    @JsonProperty("capabilities") Map<String, Object> capabilities,
    @JsonProperty("product_features") Map<String, Object> productFeatures,
    @JsonProperty("enabled_tools") List<String> enabledTools,
    @JsonProperty("input_modalities") List<String> inputModalities,
    @JsonProperty("output_modalities") List<String> outputModalities,
    @JsonProperty("aliases") List<String> aliases,
    @JsonProperty("version") String version,
    @JsonProperty("pricing") Map<String, Object> pricing
) {
    public ModelItemDto {
        if (object == null || object.isBlank()) {
            object = "model";
        }
        if (ownedBy == null || ownedBy.isBlank()) {
            ownedBy = "system";
        }
    }

    public static ModelItemDto fromEntity(ModelEntity entity) {
        long createdEpoch = entity.getUpstreamCreatedAt() != null
            ? entity.getUpstreamCreatedAt().getEpochSecond()
            : (entity.getCreatedAt() != null ? entity.getCreatedAt().getEpochSecond() : Instant.now().getEpochSecond());

        return new ModelItemDto(
            entity.getId(),
            "model",
            createdEpoch,
            entity.getOwnedBy() != null ? entity.getOwnedBy() : "system",
            entity.getSlug(),
            entity.getProviderId(),
            entity.getModelId(),
            entity.getCategory(),
            entity.isDefault(),
            entity.getMaxTokens(),
            entity.getContextWindow(),
            entity.getMaxOutputTokens(),
            entity.getTitle(),
            entity.getDescription() != null ? entity.getDescription() : "",
            entity.getTags() != null ? List.copyOf(entity.getTags()) : List.of(),
            entity.getCapabilities() != null ? new LinkedHashMap<>(entity.getCapabilities()) : Map.of(),
            entity.getProductFeatures() != null ? new LinkedHashMap<>(entity.getProductFeatures()) : Map.of(),
            entity.getEnabledTools() != null ? List.copyOf(entity.getEnabledTools()) : List.of(),
            entity.getInputModalities() != null ? List.copyOf(entity.getInputModalities()) : List.of(),
            entity.getOutputModalities() != null ? List.copyOf(entity.getOutputModalities()) : List.of(),
            entity.getAliases() != null ? List.copyOf(entity.getAliases()) : List.of(),
            entity.getModelVersion() != null ? entity.getModelVersion() : "1.0",
            entity.getPricing() != null ? new LinkedHashMap<>(entity.getPricing()) : Map.of()
        );
    }
}
