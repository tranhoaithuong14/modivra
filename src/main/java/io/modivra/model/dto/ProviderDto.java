package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.modivra.model.entity.ModelProviderEntity;

public record ProviderDto(
    @JsonProperty("id") String id,
    @JsonProperty("display_name") String displayName,
    @JsonProperty("api_mode") String apiMode,
    @JsonProperty("base_url") String baseUrl,
    @JsonProperty("is_enabled") boolean isEnabled,
    @JsonProperty("configured") boolean configured,
    @JsonProperty("supports_discovery") boolean supportsDiscovery
) {
    public static ProviderDto fromEntity(ModelProviderEntity entity, boolean isConfigured) {
        return new ProviderDto(
            entity.getId(),
            entity.getDisplayName(),
            entity.getApiMode() != null ? entity.getApiMode().getValue() : "openai_compatible",
            entity.getBaseUrl(),
            entity.isEnabled(),
            isConfigured,
            entity.isSupportsDiscovery()
        );
    }
}
