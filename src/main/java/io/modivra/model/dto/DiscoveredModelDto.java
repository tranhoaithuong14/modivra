package io.modivra.model.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DiscoveredModelDto(
    String modelId,
    String slug,
    String title,
    String description,
    int contextWindow,
    int maxOutputTokens,
    int maxTokens,
    String category,
    String ownedBy,
    String modelVersion,
    String fingerprint,
    List<String> inputModalities,
    List<String> outputModalities,
    List<String> aliases,
    List<String> tags,
    Map<String, Object> capabilities,
    Map<String, Object> productFeatures,
    List<String> enabledTools,
    Map<String, Object> pricing,
    Instant upstreamCreatedAt
) {
    public DiscoveredModelDto {
        if (slug == null || slug.isBlank()) {
            slug = modelId;
        }
        if (title == null || title.isBlank()) {
            title = modelId;
        }
        if (description == null) description = "";
        if (contextWindow <= 0) contextWindow = 131072;
        if (maxOutputTokens <= 0) maxOutputTokens = 16384;
        if (maxTokens <= 0) maxTokens = contextWindow;
        if (category == null || category.isBlank()) category = "general";
        if (ownedBy == null || ownedBy.isBlank()) ownedBy = "system";
        if (inputModalities == null || inputModalities.isEmpty()) inputModalities = List.of("text");
        if (outputModalities == null || outputModalities.isEmpty()) outputModalities = List.of("text");
        if (aliases == null) aliases = List.of();
        if (tags == null) tags = List.of();
        if (capabilities == null) capabilities = Map.of();
        if (productFeatures == null) productFeatures = Map.of();
        if (enabledTools == null) enabledTools = List.of();
        if (pricing == null) pricing = Map.of();
    }
}
