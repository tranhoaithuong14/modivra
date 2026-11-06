package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ModelsResponseDto(
    @JsonProperty("models") List<ModelItemDto> models,
    @JsonProperty("categories") List<CategoryDto> categories
) {
    public ModelsResponseDto {
        models = models != null ? List.copyOf(models) : List.of();
        categories = categories != null ? List.copyOf(categories) : List.of();
    }
}
