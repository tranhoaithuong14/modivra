package io.modivra.model.discovery;

import tools.jackson.databind.JsonNode;
import io.modivra.model.catalog.BuiltinModelSpecCatalog;
import io.modivra.model.catalog.ModelSpec;
import io.modivra.model.dto.DiscoveredModelDto;
import io.modivra.model.entity.ApiMode;
import io.modivra.model.entity.ModelProviderEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaDiscoveryAdapter implements ModelDiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(OllamaDiscoveryAdapter.class);

    private final BuiltinModelSpecCatalog catalog;
    private final RestClient.Builder restClientBuilder;

    public OllamaDiscoveryAdapter(
        BuiltinModelSpecCatalog catalog,
        @Autowired(required = false) RestClient.Builder restClientBuilder
    ) {
        this.catalog = catalog;
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
    }

    @Override
    public boolean supports(ApiMode apiMode, String providerId) {
        return "ollama".equalsIgnoreCase(providerId);
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public List<DiscoveredModelDto> discoverModels(ModelProviderEntity provider) {
        String baseUrl = provider.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return fallbackBaseline();
        }

        // Ollama native API is at root /api/tags, strip /v1 if present
        String rootUrl = baseUrl.endsWith("/v1")
            ? baseUrl.substring(0, baseUrl.length() - 3)
            : baseUrl;

        List<DiscoveredModelDto> result = new ArrayList<>();
        RestClient client = restClientBuilder.baseUrl(rootUrl).build();

        // Step 1: Call GET /api/tags
        try {
            JsonNode root = client.get()
                .uri("/api/tags")
                .retrieve()
                .body(JsonNode.class);

            if (root != null && root.has("models") && root.get("models").isArray()) {
                for (JsonNode m : root.get("models")) {
                    String name = m.path("name").asText();
                    if (name.isBlank() || catalog.isExcludedNonChatModel(name)) {
                        continue;
                    }
                    result.add(parseOllamaModel(m, name));
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception ex) {
            log.debug("Ollama /api/tags connection failed (local daemon may be offline): {}", ex.getMessage());
        }

        // Step 2: Fallback to /models if /api/tags was 404
        try {
            RestClient v1Client = restClientBuilder.baseUrl(baseUrl).build();
            JsonNode root = v1Client.get()
                .uri("/models")
                .retrieve()
                .body(JsonNode.class);

            if (root != null && root.has("data") && root.get("data").isArray()) {
                for (JsonNode m : root.get("data")) {
                    String id = m.path("id").asText();
                    if (!id.isBlank() && !catalog.isExcludedNonChatModel(id)) {
                        result.add(buildFallbackOllamaModel(id));
                    }
                }
                if (!result.isEmpty()) {
                    return result;
                }
            }
        } catch (Exception ex) {
            log.debug("Ollama /models fallback failed: {}", ex.getMessage());
        }

        // Step 3: Offline fallback
        return fallbackBaseline();
    }

    private DiscoveredModelDto parseOllamaModel(JsonNode node, String name) {
        String modelId = name;
        String slug = name.endsWith(":latest")
            ? name.substring(0, name.length() - 7)
            : name;

        List<String> aliases = new ArrayList<>();
        aliases.add(slug);
        if (name.endsWith(":latest")) {
            aliases.add(name);
        }

        Optional<ModelSpec> specOpt = catalog.findSpec(name);
        int contextWindow = specOpt.map(ModelSpec::contextWindow).orElse(131072);
        int maxOutput = specOpt.map(ModelSpec::maxOutputTokens).orElse(16384);
        String category = specOpt.map(ModelSpec::category).orElse("ollama");
        String ownedBy = specOpt.map(ModelSpec::ownedBy).orElse("ollama");
        String title = specOpt.map(ModelSpec::title).orElse(formatTitle(name));
        Map<String, Object> caps = specOpt.map(ModelSpec::capabilities)
            .orElse(Map.of("reasoning", name.contains("r1")));

        List<String> inMods = List.of("text");
        if (name.contains("llava") || name.contains("vision")) {
            inMods = List.of("text", "image");
        }

        return new DiscoveredModelDto(
            modelId, slug, title, "Local Ollama model",
            contextWindow, maxOutput, contextWindow,
            category, ownedBy, null, null,
            inMods, List.of("text"),
            aliases, List.of("local"), caps, Map.of(), List.of(), Map.of(), Instant.now()
        );
    }

    private DiscoveredModelDto buildFallbackOllamaModel(String modelId) {
        return parseOllamaModel(null, modelId);
    }

    private List<DiscoveredModelDto> fallbackBaseline() {
        Optional<ModelSpec> r1 = catalog.findSpec("deepseek-r1:7b");
        Optional<ModelSpec> llama = catalog.findSpec("llama3:latest");
        List<DiscoveredModelDto> list = new ArrayList<>();
        r1.ifPresent(s -> list.add(toDto(s)));
        llama.ifPresent(s -> list.add(toDto(s)));
        return list;
    }

    private DiscoveredModelDto toDto(ModelSpec spec) {
        return new DiscoveredModelDto(
            spec.modelId(), spec.slug(), spec.title(), spec.description(),
            spec.contextWindow(), spec.maxOutputTokens(), spec.maxTokens(),
            spec.category(), spec.ownedBy(), null, null,
            spec.inputModalities(), spec.outputModalities(),
            spec.aliases(), spec.tags(), spec.capabilities(), Map.of(), List.of(), spec.pricing(), Instant.now()
        );
    }

    private String formatTitle(String name) {
        if (name.startsWith("deepseek-r1")) return "DeepSeek R1 (" + name + ")";
        if (name.startsWith("llama3")) return "Llama 3 (" + name + ")";
        return "Ollama: " + name;
    }
}
