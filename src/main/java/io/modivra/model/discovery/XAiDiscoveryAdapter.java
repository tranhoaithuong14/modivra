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
public class XAiDiscoveryAdapter implements ModelDiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(XAiDiscoveryAdapter.class);

    private final BuiltinModelSpecCatalog catalog;
    private final Environment environment;
    private final RestClient.Builder restClientBuilder;

    public XAiDiscoveryAdapter(
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
        return "xai".equalsIgnoreCase(providerId);
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public List<DiscoveredModelDto> discoverModels(ModelProviderEntity provider) {
        String apiKey = resolveApiKey(provider);
        String baseUrl = provider.getBaseUrl();
        List<DiscoveredModelDto> result = new ArrayList<>();

        if (baseUrl != null && !baseUrl.isBlank()) {
            RestClient client = restClientBuilder.baseUrl(baseUrl).build();

            // Step 1: Try primary protocol GET /language-models
            try {
                JsonNode root = client.get()
                    .uri("/language-models")
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(JsonNode.class);

                if (root != null && root.has("models") && root.get("models").isArray()) {
                    for (JsonNode m : root.get("models")) {
                        String modelId = m.path("id").asText();
                        if (modelId.isBlank() || catalog.isExcludedNonChatModel(modelId)) {
                            continue;
                        }
                        result.add(parseLanguageModel(m, modelId));
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (Exception ex) {
                log.warn("xAI /language-models discovery failed: {}, falling back to /models", ex.getMessage());
            }

            // Step 2: Fallback to GET /models
            try {
                JsonNode root = client.get()
                    .uri("/models")
                    .headers(h -> {
                        if (apiKey != null && !apiKey.isBlank()) {
                            h.setBearerAuth(apiKey);
                        }
                    })
                    .retrieve()
                    .body(JsonNode.class);

                if (root != null && root.has("data") && root.get("data").isArray()) {
                    for (JsonNode m : root.get("data")) {
                        String modelId = m.path("id").asText();
                        if (modelId.isBlank() || catalog.isExcludedNonChatModel(modelId)) {
                            continue;
                        }
                        result.add(buildFromCatalogFallback(modelId));
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (Exception ex) {
                log.warn("xAI /models discovery failed: {}", ex.getMessage());
            }
        }

        // Step 3: Hermetic / Offline fallback if network is unreachable
        return fallbackBaseline();
    }

    private DiscoveredModelDto parseLanguageModel(JsonNode node, String modelId) {
        String slug = modelId.equals("grok-2-latest") ? "grok-2" : modelId;
        String version = node.has("version") && !node.get("version").isNull() ? node.get("version").asText() : null;
        String fingerprint = node.has("fingerprint") && !node.get("fingerprint").isNull() ? node.get("fingerprint").asText() : null;

        List<String> inMods = new ArrayList<>();
        if (node.has("input_modalities")) {
            node.get("input_modalities").forEach(n -> inMods.add(n.asText()));
        }
        List<String> outMods = new ArrayList<>();
        if (node.has("output_modalities")) {
            node.get("output_modalities").forEach(n -> outMods.add(n.asText()));
        }
        List<String> aliases = new ArrayList<>();
        if (node.has("aliases")) {
            node.get("aliases").forEach(n -> aliases.add(n.asText()));
        }
        if (modelId.equals("grok-2-latest") && !aliases.contains("grok-2-latest")) {
            aliases.add("grok-2-latest");
        }

        Map<String, Object> pricing = new HashMap<>();
        if (node.has("prompt_text_token_price")) {
            pricing.put("prompt_text_token_price", node.get("prompt_text_token_price").asLong());
        }
        if (node.has("completion_text_token_price")) {
            pricing.put("completion_text_token_price", node.get("completion_text_token_price").asLong());
        }

        // Enrich with catalog for limits
        Optional<ModelSpec> specOpt = catalog.findSpec(modelId);
        int contextWindow = specOpt.map(ModelSpec::contextWindow).orElse(131072);
        int maxOutput = specOpt.map(ModelSpec::maxOutputTokens).orElse(16384);
        String title = specOpt.map(ModelSpec::title).orElse(formatTitle(slug));
        String desc = specOpt.map(ModelSpec::description).orElse("");
        String category = specOpt.map(ModelSpec::category).orElse("grok_4");
        List<String> tags = specOpt.map(ModelSpec::tags).orElse(List.of());
        Map<String, Object> caps = specOpt.map(ModelSpec::capabilities).orElse(Map.of());

        return new DiscoveredModelDto(
            modelId, slug, title, desc,
            contextWindow, maxOutput, contextWindow,
            category, "xai", version, fingerprint,
            inMods.isEmpty() ? List.of("text") : inMods,
            outMods.isEmpty() ? List.of("text") : outMods,
            aliases, tags, caps, Map.of(), List.of(), pricing, Instant.now()
        );
    }

    private DiscoveredModelDto buildFromCatalogFallback(String modelId) {
        Optional<ModelSpec> specOpt = catalog.findSpec(modelId);
        if (specOpt.isPresent()) {
            ModelSpec spec = specOpt.get();
            return new DiscoveredModelDto(
                spec.modelId(), spec.slug(), spec.title(), spec.description(),
                spec.contextWindow(), spec.maxOutputTokens(), spec.maxTokens(),
                spec.category(), spec.ownedBy(), null, null,
                spec.inputModalities(), spec.outputModalities(),
                spec.aliases(), spec.tags(), spec.capabilities(), Map.of(), List.of(), spec.pricing(), Instant.now()
            );
        }
        String slug = modelId.equals("grok-2-latest") ? "grok-2" : modelId;
        return new DiscoveredModelDto(
            modelId, slug, formatTitle(slug), "",
            131072, 16384, 131072,
            "grok_4", "xai", null, null,
            List.of("text"), List.of("text"),
            List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now()
        );
    }

    private List<DiscoveredModelDto> fallbackBaseline() {
        return List.of(
            buildFromCatalogFallback("grok-4.7"),
            buildFromCatalogFallback("grok-2-latest"),
            buildFromCatalogFallback("grok-4.6")
        );
    }

    private String formatTitle(String slug) {
        return switch (slug) {
            case "grok-4.7" -> "Grok 4.7";
            case "grok-2", "grok-2-latest" -> "Grok 2";
            case "grok-4.6" -> "Grok 4.6";
            default -> "Grok (" + slug + ")";
        };
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
