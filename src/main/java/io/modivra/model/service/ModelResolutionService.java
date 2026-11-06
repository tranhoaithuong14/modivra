package io.modivra.model.service;

import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;
import io.modivra.model.exception.ModelAmbiguityException;
import io.modivra.model.exception.ModelDisabledException;
import io.modivra.model.exception.ModelNotFoundException;
import io.modivra.model.repository.ModelProviderRepository;
import io.modivra.model.repository.ModelRepository;
import io.modivra.model.routing.ResolvedChatModel;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelResolutionService {

    private final ModelRepository modelRepository;
    private final ModelProviderRepository providerRepository;

    public ModelResolutionService(
        ModelRepository modelRepository,
        ModelProviderRepository providerRepository
    ) {
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
    }

    /**
     * Resolves and validates a requested model identifier using the 5-stage resolution ladder:
     * - Stage 0: Null or blank -> active default model (is_default = TRUE, xai/grok-4.7).
     * - Slash validation & provider check: split("/", 2) for canonical / multi-vendor IDs.
     * - Stage 1: Exact canonical ID match (e.g. "xai/grok-4.7", "openrouter/anthropic/claude-3-opus").
     * - Stage 2: Provider ID + short slug or model_id match (e.g. "openrouter/claude-3.5-sonnet", "xai/grok-2-latest").
     * - Stage 3: Provider ID + JSONB alias match (e.g. "xai/latest", "openrouter/sonnet").
     * - Stage 4: Global JSONB alias match across enabled providers with display_order disambiguation.
     * - Stage 5: Short slug (or model_id) match across enabled providers with display_order disambiguation.
     */
    @Transactional(readOnly = true)
    public ResolvedChatModel resolveAndValidateChatModel(String requestedModel) {
        // Stage 0: Empty or null request resolves to the single active default model
        if (requestedModel == null || requestedModel.isBlank()) {
            ModelEntity defaultModel = modelRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new ModelNotFoundException("No default model configured in registry"));
            validateEnabledAndTextCapable(defaultModel, "default");
            return toResolvedChatModel(defaultModel);
        }

        String input = requestedModel.trim();

        // Check if input contains slash ("/")
        if (input.contains("/")) {
            String[] parts = input.split("/", 2);
            if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new ModelNotFoundException("Invalid model identifier format: '" + requestedModel + "'");
            }
            String providerPrefix = parts[0].trim();
            String remainder = parts[1].trim();
            if (remainder.startsWith("/") || remainder.endsWith("/")) {
                throw new ModelNotFoundException("Invalid model identifier format: '" + requestedModel + "'");
            }

            Optional<ModelProviderEntity> providerOpt = providerRepository.findById(providerPrefix);
            if (providerOpt.isPresent()) {
                ModelProviderEntity provider = providerOpt.get();
                if (!provider.isEnabled()) {
                    throw new ModelDisabledException("Provider '" + providerPrefix + "' is disabled");
                }

                // Stage 1: Exact canonical ID match (provider_id/model_id)
                Optional<ModelEntity> canonicalMatch = modelRepository.findByIdWithProvider(input)
                    .or(() -> modelRepository.findById(input));
                if (canonicalMatch.isPresent()) {
                    ModelEntity model = canonicalMatch.get();
                    validateEnabledAndTextCapable(model, requestedModel);
                    return toResolvedChatModel(model);
                }

                // Stage 2: Provider ID + slug match (or Provider ID + model_id match)
                Optional<ModelEntity> slugOrModelIdMatch = modelRepository.findByProviderIdAndSlug(providerPrefix, remainder)
                    .or(() -> modelRepository.findByProviderIdAndModelId(providerPrefix, remainder));
                if (slugOrModelIdMatch.isPresent()) {
                    ModelEntity model = slugOrModelIdMatch.get();
                    validateEnabledAndTextCapable(model, requestedModel);
                    return toResolvedChatModel(model);
                }

                // Stage 3: Provider ID + JSONB alias match
                List<ModelEntity> aliasMatches = modelRepository.findByProviderIdAndAliasValue(providerPrefix, remainder);
                if (!aliasMatches.isEmpty()) {
                    ModelEntity model = aliasMatches.getFirst();
                    validateEnabledAndTextCapable(model, requestedModel);
                    return toResolvedChatModel(model);
                }

                // Check if a disabled model under this provider matches the alias
                List<ModelEntity> allAliasMatches = modelRepository.findAllByAliasValue(remainder).stream()
                    .filter(m -> providerPrefix.equals(m.getProviderId()))
                    .toList();
                if (!allAliasMatches.isEmpty()) {
                    throw new ModelDisabledException("Model '" + requestedModel + "' is disabled");
                }

                throw new ModelNotFoundException(
                    "Model '" + remainder + "' not found under provider '" + providerPrefix + "'"
                );
            } else {
                // Provider prefix is not a registered provider ID; check if input is a vendor-prefixed model_id (e.g. "anthropic/claude-3.5-sonnet")
                List<ModelEntity> modelIdMatches = modelRepository.findAllByModelId(input);
                if (!modelIdMatches.isEmpty()) {
                    return resolveCandidateList(modelIdMatches, requestedModel);
                }
                throw new ModelNotFoundException("Unknown provider or model identifier: '" + requestedModel + "'");
            }
        }

        // Unqualified identifier (no "/"):
        // Check exact short slug matches among enabled models/providers (Stage 5)
        List<ModelEntity> activeSlugMatches = modelRepository.findBySlugAndIsEnabledTrue(input);
        if (!activeSlugMatches.isEmpty()) {
            return disambiguateActiveMatches(activeSlugMatches, requestedModel);
        }

        // Check if slug exists on a disabled model or disabled provider (fail fast with ModelDisabledException!)
        List<ModelEntity> allSlugMatches = modelRepository.findAllBySlug(input);
        if (!allSlugMatches.isEmpty()) {
            throw new ModelDisabledException("Model or provider for '" + requestedModel + "' is disabled");
        }

        // Stage 4: Global JSONB alias match across enabled models & providers
        List<ModelEntity> activeAliasMatches = modelRepository.findByGlobalAliasValue(input);
        if (!activeAliasMatches.isEmpty()) {
            return disambiguateActiveMatches(activeAliasMatches, requestedModel);
        }

        // Check if alias exists on a disabled model or disabled provider
        List<ModelEntity> allAliasMatches = modelRepository.findAllByAliasValue(input);
        if (!allAliasMatches.isEmpty()) {
            throw new ModelDisabledException("Model or provider for alias '" + requestedModel + "' is disabled");
        }

        // Fallback check on raw model_id (e.g. "llama3:latest")
        List<ModelEntity> allModelIdMatches = modelRepository.findAllByModelId(input);
        if (!allModelIdMatches.isEmpty()) {
            return resolveCandidateList(allModelIdMatches, requestedModel);
        }

        throw new ModelNotFoundException("Model not found: '" + requestedModel + "'");
    }

    private ResolvedChatModel resolveCandidateList(List<ModelEntity> candidates, String requestedModel) {
        List<ModelEntity> active = candidates.stream()
            .filter(m -> m.isEnabled() && m.getProvider() != null && m.getProvider().isEnabled())
            .toList();
        if (!active.isEmpty()) {
            return disambiguateActiveMatches(active, requestedModel);
        }
        throw new ModelDisabledException("Model or provider for '" + requestedModel + "' is disabled");
    }

    private ResolvedChatModel disambiguateActiveMatches(List<ModelEntity> activeMatches, String requestedModel) {
        if (activeMatches.size() == 1) {
            ModelEntity single = activeMatches.getFirst();
            validateEnabledAndTextCapable(single, requestedModel);
            return toResolvedChatModel(single);
        }

        List<ModelEntity> sorted = activeMatches.stream()
            .sorted(Comparator
                .comparingInt((ModelEntity m) -> m.getProvider().getDisplayOrder())
                .thenComparingInt(ModelEntity::getDisplayOrder)
                .thenComparing(ModelEntity::getId))
            .toList();

        ModelEntity first = sorted.get(0);
        ModelEntity second = sorted.get(1);

        if (first.getProvider().getDisplayOrder() == second.getProvider().getDisplayOrder()) {
            List<String> candidateIds = sorted.stream().map(ModelEntity::getId).toList();
            throw new ModelAmbiguityException(
                "Ambiguous model identifier '" + requestedModel + "' matches multiple models with equal priority: "
                    + candidateIds + ". Please specify canonical provider_id/model_id."
            );
        }

        validateEnabledAndTextCapable(first, requestedModel);
        return toResolvedChatModel(first);
    }

    private void validateEnabledAndTextCapable(ModelEntity model, String requestedModel) {
        ModelProviderEntity provider = model.getProvider();
        if (provider == null || !provider.isEnabled()) {
            throw new ModelDisabledException("Provider for model '" + requestedModel + "' is disabled");
        }
        if (!model.isEnabled()) {
            throw new ModelDisabledException("Model '" + requestedModel + "' is disabled");
        }
        List<String> outMods = model.getOutputModalities();
        boolean supportsText = outMods != null && outMods.stream().anyMatch(m -> "text".equalsIgnoreCase(m));
        if (!supportsText) {
            throw new ModelDisabledException("Model '" + requestedModel + "' does not support text output modality");
        }
    }

    private ResolvedChatModel toResolvedChatModel(ModelEntity model) {
        ModelProviderEntity provider = model.getProvider();
        if (provider != null) {
            provider.getId();
            provider.getDisplayName();
            provider.getApiMode();
            provider.getBaseUrl();
            provider.getApiKeyEnv();
            provider.isEnabled();
            provider.isSupportsDiscovery();
        }
        return new ResolvedChatModel(
            model.getId(),
            model.getModelId(),
            provider,
            model
        );
    }
}
