package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record CategoryDto(
    @JsonProperty("category") String category,
    @JsonProperty("human_category_name") String humanCategoryName,
    @JsonProperty("subscription_level") String subscriptionLevel,
    @JsonProperty("default_model") String defaultModel,
    @JsonProperty("browsing_model") String browsingModel,
    @JsonProperty("code_interpreter_model") String codeInterpreterModel,
    @JsonProperty("plugins_model") String pluginsModel
) {
    public static CategoryDto of(String category, String humanCategoryName, String defaultModel) {
        return new CategoryDto(
            category,
            humanCategoryName,
            "free",
            defaultModel,
            defaultModel,
            defaultModel,
            defaultModel
        );
    }
}
