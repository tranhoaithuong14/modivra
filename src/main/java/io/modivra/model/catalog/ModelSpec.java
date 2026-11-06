package io.modivra.model.catalog;

import java.util.List;
import java.util.Map;

public record ModelSpec(
    String modelId,
    String slug,
    String title,
    String description,
    int contextWindow,
    int maxOutputTokens,
    int maxTokens,
    String category,
    String ownedBy,
    List<String> inputModalities,
    List<String> outputModalities,
    List<String> aliases,
    List<String> tags,
    Map<String, Object> capabilities,
    Map<String, Object> pricing
) {
    public static ModelSpec of(
        String modelId,
        String slug,
        String title,
        String description,
        int contextWindow,
        int maxOutputTokens,
        String category,
        String ownedBy,
        List<String> inputModalities,
        List<String> outputModalities,
        List<String> aliases,
        List<String> tags,
        Map<String, Object> capabilities,
        Map<String, Object> pricing
    ) {
        return new ModelSpec(
            modelId, slug, title, description,
            contextWindow, maxOutputTokens, contextWindow,
            category, ownedBy,
            inputModalities, outputModalities,
            aliases, tags, capabilities, pricing
        );
    }
}
