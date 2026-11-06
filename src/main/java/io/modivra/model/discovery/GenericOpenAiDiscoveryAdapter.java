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
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class GenericOpenAiDiscoveryAdapter implements ModelDiscoveryAdapter {

    private static final Logger log = LoggerFactory.getLogger(GenericOpenAiDiscoveryAdapter.class);

    private final BuiltinModelSpecCatalog catalog;
    private final Environment environment;
    private final RestClient.Builder restClientBuilder;

    public GenericOpenAiDiscoveryAdapter(
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
        return apiMode == ApiMode.OPENAI_COMPATIBLE || apiMode == ApiMode.OPENAI_RESPONSES;
    }

    @Override
    public int getOrder() {
        return 100;
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
                    })
                    .retrieve()
                    .body(JsonNode.class);

                if (root != null && root.has("data") && root.get("data").isArray()) {
                    for (JsonNode m : root.get("data")) {
                        String modelId = m.path("id").asText();
                        if (modelId.isBlank() || catalog.isExcludedNonChatModel(modelId)) {
                            continue;
                        }
                        result.add(enrichModel(modelId, provider.getId()));
                    }
                    if (!result.isEmpty()) {
                        return result;
                    }
                }
            } catch (Exception ex) {
                log.warn("Generic OpenAI discovery failed for {}: {}", provider.getId(), ex.getMessage());
            }
        }

        // Offline fallback if provider is "openai"
        if ("openai".equalsIgnoreCase(provider.getId())) {
            Optional<ModelSpec> gpt4o = catalog.findSpec("gpt-4o");
            gpt4o.ifPresent(spec -> result.add(new DiscoveredModelDto(
                spec.modelId(), spec.slug(), spec.title(), spec.description(),
                spec.contextWindow(), spec.maxOutputTokens(), spec.maxTokens(),
                spec.category(), spec.ownedBy(), null, null,
                spec.inputModalities(), spec.outputModalities(),
                spec.aliases(), spec.tags(), spec.capabilities(), Map.of(), List.of(), spec.pricing(), Instant.now()
            )));
        }

        return result;
    }

    private DiscoveredModelDto enrichModel(String modelId, String providerId) {
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

        // Generic safe defaults
        String slug = modelId;
        String title = formatTitle(modelId);
        return new DiscoveredModelDto(
            modelId, slug, title, "",
            131072, 16384, 131072,
            "general", providerId, null, null,
            List.of("text"), List.of("text"),
            List.of(), List.of(), Map.of("tools", true), Map.of(), List.of(), Map.of(), Instant.now()
        );
    }

    private String formatTitle(String modelId) {
        if (modelId.startsWith("gpt-")) {
            return modelId.toUpperCase();
        }
        return modelId;
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
