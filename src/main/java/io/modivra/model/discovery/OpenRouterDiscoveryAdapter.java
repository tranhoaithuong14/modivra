package io.modivra.model.discovery;

import tools.jackson.databind.JsonNode;
import io.modivra.model.catalog.BuiltinModelSpecCatalog;
import io.modivra.model.catalog.ModelSpec;
import io.modivra.model.dto.DiscoveredModelDto;
import io.modivra.model.entity.ApiMode;
import io.modivra.model.entity.ModelProviderEntity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenRouterDiscoveryAdapter implements ModelDiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterDiscoveryAdapter.class);

    private final BuiltinModelSpecCatalog catalog;
    private final Environment environment;
    private final RestClient.Builder restClientBuilder;

    public OpenRouterDiscoveryAdapter(
        BuiltinModelSpecCatalog catalog,
        Environment environment,
        @Autowired(required = false) RestClient.Builder restClientBuilder
    ) {
        this.catalog = catalog;
        this.environment = environment;
        this.restClientBuilder = restClientBuilder != null ? restClientBuilder : RestClient.builder();
    }

    @Override
    public boolean supports(ApiMode apiMode, String providerId) {
        return "openrouter".equalsIgnoreCase(providerId);
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public List<DiscoveredModelDto> discoverModels(ModelProviderEntity provider) {
        String apiKey = resolveApiKey(provider);
        String baseUrl = provider.getBaseUrl();
        List<DiscoveredModelDto> result = new ArrayList<>();

        if (baseUrl != null && !baseUrl.isBlank()) {
            RestClient client = restClientBuilder.baseUrl(baseUrl).build();

            try {
                JsonNode root = client.get()
                    .uri("/models")
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                        }
                        h.set("HTTP-Referer", "https://modivra.io");
                        h.set("X-Title", "Modivra");
                    })
                    .retrieve()
                    .body(JsonNode.class);

                if (root != null && root.has("data") && root.get("data").isArray()) {
                    for (JsonNode m : root.get("data")) {
                        String modelId = m.path("id").asText();
                        if (modelId.isBlank() || catalog.isExcludedNonChatModel(modelId)) {
                            continue;
                        }
                        // Filter non-text output
                        JsonNode arch = m.path("architecture");
                        if (arch.has("output_modalities")) {
                            boolean hasText = false;
                            for (JsonNode out : arch.get("output_modalities")) {
                                if ("text".equalsIgnoreCase(out.asText())) {
                                    hasText = true;
                                    break;
                                }
                            }
                            if (!hasText) {
                                continue;
                            }
                        }

                        result.add(parseOpenRouterModel(m, modelId));
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (Exception ex) {
                log.warn("OpenRouter /models discovery failed: {}", ex.getMessage());
            }
        }

        // Offline fallback
        return fallbackBaseline();
    }

    private DiscoveredModelDto parseOpenRouterModel(JsonNode node, String modelId) {
        // Derive short slug from multi-vendor ID (e.g. "anthropic/claude-3.5-sonnet" -> "claude-3.5-sonnet")
        int lastSlash = modelId.lastIndexOf('/');
        String slug = (lastSlash >= 0 && lastSlash < modelId.length() - 1)
            ? modelId.substring(lastSlash + 1)
            : modelId;

        String title = node.path("name").asText(slug);
        String desc = node.path("description").asText("");

        // Limits
        int contextLength = node.path("context_length").asInt(131072);
        JsonNode topProvider = node.path("top_provider");
        int maxCompletion = topProvider.path("max_completion_tokens").asInt(8192);
        if (topProvider.has("context_length") && topProvider.path("context_length").asInt() > 0) {
            contextLength = topProvider.path("context_length").asInt();
        }

        // Modalities
        List<String> inMods = new ArrayList<>();
        List<String> outMods = new ArrayList<>();
        JsonNode arch = node.path("architecture");
        if (arch.has("input_modalities")) {
            arch.get("input_modalities").forEach(n -> inMods.add(n.asText()));
        }
        if (arch.has("output_modalities")) {
            arch.get("output_modalities").forEach(n -> outMods.add(n.asText()));
        }

        // Pricing
        Map<String, Object> pricing = new HashMap<>();
        JsonNode pNode = node.path("pricing");
        if (pNode.isObject()) {
            pNode.properties().forEach(entry -> pricing.put(entry.getKey(), entry.getValue().asText("")));
        }

        // OwnedBy from vendor prefix e.g. "anthropic"
        String ownedBy = lastSlash > 0 ? modelId.substring(0, lastSlash) : "openrouter";

        // Aliases & tags
        List<String> aliases = new ArrayList<>();
        aliases.add(slug);
        Optional<ModelSpec> specOpt = catalog.findSpec(modelId);
        specOpt.ifPresent(spec -> aliases.addAll(spec.aliases()));

        List<String> tags = specOpt.map(ModelSpec::tags).orElse(List.of("openrouter"));
        Map<String, Object> capabilities = specOpt.map(ModelSpec::capabilities)
            .orElse(Map.of("tools", true, "vision", inMods.contains("image")));

        return new DiscoveredModelDto(
            modelId, slug, title, desc,
            contextLength, maxCompletion, contextLength,
            "claude", ownedBy, null, null,
            inMods.isEmpty() ? List.of("text") : inMods,
            outMods.isEmpty() ? List.of("text") : outMods,
            aliases.stream().distinct().toList(), tags, capabilities, Map.of(), List.of(), pricing, Instant.now()
        );
    }

    private List<DiscoveredModelDto> fallbackBaseline() {
        Optional<ModelSpec> sonnet = catalog.findSpec("anthropic/claude-3.5-sonnet");
        Optional<ModelSpec> opus = catalog.findSpec("anthropic/claude-3-opus");
        List<DiscoveredModelDto> list = new ArrayList<>();
        sonnet.ifPresent(s -> list.add(toDto(s)));
        opus.ifPresent(s -> list.add(toDto(s)));
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

    private String resolveApiKey(ModelProviderEntity provider) {
        if (provider.getApiKeyEnv() != null && !provider.getApiKeyEnv().isBlank()) {
            if (environment != null) {
                String val = environment.getProperty(provider.getApiKeyEnv());
                if (val != null && !val.isBlank()) return val;
            }
            String val = System.getenv(provider.getApiKeyEnv());
            if (val != null && !val.isBlank()) return val;
        }
        return null;
    }
}
