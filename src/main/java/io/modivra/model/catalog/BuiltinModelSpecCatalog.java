package io.modivra.model.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class BuiltinModelSpecCatalog {

    private final Map<String, ModelSpec> catalog = new LinkedHashMap<>();

    public BuiltinModelSpecCatalog() {
        // xAI models
        register(ModelSpec.of(
            "grok-4.7", "grok-4.7", "Grok 4.7",
            "xAI flagship intelligence model with reasoning capabilities",
            131072, 16384, "grok_4", "xai",
            List.of("text", "image"), List.of("text"),
            List.of("latest", "grok-4"), List.of("flagship", "reasoning"),
            Map.of("tools", true, "vision", true, "reasoning", true),
            Map.of("prompt_text_token_price", 5000000, "completion_text_token_price", 15000000)
        ));

        register(ModelSpec.of(
            "grok-2-latest", "grok-2", "Grok 2",
            "xAI standard production model",
            131072, 8192, "grok_2", "xai",
            List.of("text"), List.of("text"),
            List.of("grok-2-latest", "grok-2-1212"), List.of("production"),
            Map.of("tools", true, "vision", false, "reasoning", false),
            Map.of("prompt_text_token_price", 2000000, "completion_text_token_price", 10000000)
        ));

        register(ModelSpec.of(
            "grok-4.6", "grok-4.6", "Grok 4.6",
            "xAI advanced reasoning model",
            131072, 16384, "grok_4", "xai",
            List.of("text", "image"), List.of("text"),
            List.of(), List.of("reasoning"),
            Map.of("tools", true, "vision", true, "reasoning", true),
            Map.of("prompt_text_token_price", 3000000, "completion_text_token_price", 15000000)
        ));

        // OpenRouter / Anthropic models
        register(ModelSpec.of(
            "anthropic/claude-3.5-sonnet", "claude-3.5-sonnet", "Claude 3.5 Sonnet (OpenRouter)",
            "Anthropic flagship reasoning model via OpenRouter",
            200000, 8192, "claude", "anthropic",
            List.of("text", "image"), List.of("text"),
            List.of("sonnet", "claude-sonnet"), List.of("flagship", "reasoning"),
            Map.of("tools", true, "vision", true, "reasoning", true),
            Map.of("prompt", "0.000003", "completion", "0.000015")
        ));

        register(ModelSpec.of(
            "anthropic/claude-3-opus", "claude-3-opus", "Claude 3 Opus (OpenRouter)",
            "Anthropic powerful model for highly complex tasks via OpenRouter",
            200000, 4096, "claude", "anthropic",
            List.of("text", "image"), List.of("text"),
            List.of("opus", "claude-opus"), List.of("flagship"),
            Map.of("tools", true, "vision", true),
            Map.of("prompt", "0.000015", "completion", "0.000075")
        ));

        // OpenAI models
        register(ModelSpec.of(
            "gpt-4o", "gpt-4o", "GPT-4o",
            "OpenAI flagship omni intelligence model",
            128000, 16384, "gpt_4o", "openai",
            List.of("text", "image"), List.of("text"),
            List.of("chatgpt-4o-latest"), List.of("flagship", "omni"),
            Map.of("tools", true, "vision", true, "reasoning", false),
            Map.of("prompt", "0.0000025", "completion", "0.00001")
        ));

        register(ModelSpec.of(
            "gpt-4o-mini", "gpt-4o-mini", "GPT-4o Mini",
            "OpenAI fast and affordable small intelligence model",
            128000, 16384, "gpt_4o", "openai",
            List.of("text", "image"), List.of("text"),
            List.of(), List.of("fast", "lightweight"),
            Map.of("tools", true, "vision", true),
            Map.of("prompt", "0.00000015", "completion", "0.0000006")
        ));

        register(ModelSpec.of(
            "o1", "o1", "OpenAI o1",
            "OpenAI reasoning model for complex science, math and code",
            200000, 100000, "o1", "openai",
            List.of("text", "image"), List.of("text"),
            List.of(), List.of("reasoning"),
            Map.of("tools", true, "vision", true, "reasoning", true),
            Map.of("prompt", "0.000015", "completion", "0.00006")
        ));

        // Ollama models
        register(ModelSpec.of(
            "deepseek-r1:7b", "deepseek-r1:7b", "DeepSeek R1 (7B)",
            "DeepSeek open reasoning model hosted locally on Ollama",
            131072, 16384, "deepseek", "deepseek",
            List.of("text"), List.of("text"),
            List.of("deepseek-r1", "r1"), List.of("local", "reasoning"),
            Map.of("tools", false, "vision", false, "reasoning", true),
            Map.of()
        ));

        register(ModelSpec.of(
            "llama3:latest", "llama3", "Llama 3 (8B)",
            "Meta Llama 3 local model on Ollama",
            8192, 8192, "llama", "meta",
            List.of("text"), List.of("text"),
            List.of("llama3", "llama-3-8b"), List.of("local", "general"),
            Map.of("tools", false, "vision", false),
            Map.of()
        ));
    }

    private void register(ModelSpec spec) {
        catalog.put(spec.modelId().toLowerCase(Locale.ROOT), spec);
    }

    public Optional<ModelSpec> findSpec(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        String key = modelId.trim().toLowerCase(Locale.ROOT);
        if (catalog.containsKey(key)) {
            return Optional.of(catalog.get(key));
        }

        // Try stripping vendor prefix e.g. "anthropic/claude-3.5-sonnet" -> "claude-3.5-sonnet"
        int slashIdx = key.lastIndexOf('/');
        if (slashIdx >= 0) {
            String shortKey = key.substring(slashIdx + 1);
            if (catalog.containsKey(shortKey)) {
                return Optional.of(catalog.get(shortKey));
            }
        }

        // Try stripping tag e.g. "deepseek-r1:7b" -> "deepseek-r1"
        int colonIdx = key.indexOf(':');
        if (colonIdx > 0) {
            String baseKey = key.substring(0, colonIdx);
            for (Map.Entry<String, ModelSpec> entry : catalog.entrySet()) {
                if (entry.getKey().startsWith(baseKey)) {
                    return Optional.of(entry.getValue());
                }
            }
        }

        return Optional.empty();
    }

    public List<ModelSpec> getAllSpecs() {
        return List.copyOf(catalog.values());
    }

    public boolean isExcludedNonChatModel(String modelId) {
        if (modelId == null) {
            return false;
        }
        String lower = modelId.toLowerCase(Locale.ROOT);
        return lower.contains("embed")
            || lower.contains("dall-e")
            || lower.contains("imagen")
            || lower.contains("whisper")
            || lower.contains("tts")
            || lower.contains("sora")
            || lower.contains("grok-imagine")
            || lower.contains("text-moderation")
            || lower.contains("babbage")
            || lower.contains("davinci")
            || lower.contains("curie")
            || lower.contains("ada");
    }
}
