package io.modivra.model.service;

import io.modivra.model.discovery.ModelDiscoveryService;
import io.modivra.model.dto.CategoryDto;
import io.modivra.model.dto.ModelItemDto;
import io.modivra.model.dto.ModelsResponseDto;
import io.modivra.model.dto.ProviderDto;
import io.modivra.model.dto.ProvidersResponseDto;
import io.modivra.model.dto.SyncResultDto;
import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;
import io.modivra.model.repository.ModelProviderRepository;
import io.modivra.model.repository.ModelRepository;
import io.modivra.model.routing.MultiProviderChatClientRegistry;
import io.modivra.model.routing.ResolvedChatModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelRegistryService {

    private final ModelRepository modelRepository;
    private final ModelProviderRepository providerRepository;
    private final ModelDiscoveryService discoveryService;
    private final MultiProviderChatClientRegistry chatClientRegistry;
    private final ModelResolutionService resolutionService;

    @Autowired
    public ModelRegistryService(
        ModelRepository modelRepository,
        ModelProviderRepository providerRepository,
        ModelDiscoveryService discoveryService,
        MultiProviderChatClientRegistry chatClientRegistry,
        ModelResolutionService resolutionService
    ) {
        this.modelRepository = modelRepository;
        this.providerRepository = providerRepository;
        this.discoveryService = discoveryService;
        this.chatClientRegistry = chatClientRegistry;
        this.resolutionService = resolutionService;
    }

    public ModelRegistryService(
        ModelRepository modelRepository,
        ModelProviderRepository providerRepository,
        ModelDiscoveryService discoveryService,
        MultiProviderChatClientRegistry chatClientRegistry
    ) {
        this(
            modelRepository,
            providerRepository,
            discoveryService,
            chatClientRegistry,
            new ModelResolutionService(modelRepository, providerRepository)
        );
    }

    @Transactional(readOnly = true)
    public ModelsResponseDto getModelsResponse() {
        List<ModelEntity> activeModels = modelRepository.findActiveCatalogModels();

        // Filter: only models supporting text output
        List<ModelEntity> chatModels = activeModels.stream()
            .filter(m -> m.getOutputModalities() != null &&
                         m.getOutputModalities().stream().anyMatch(mod -> mod.equalsIgnoreCase("text")))
            .toList();

        List<ModelItemDto> modelDtos = chatModels.stream()
            .map(ModelItemDto::fromEntity)
            .toList();

        // Build categories grouping by category
        Map<String, List<ModelEntity>> byCategory = chatModels.stream()
            .collect(Collectors.groupingBy(ModelEntity::getCategory, LinkedHashMap::new, Collectors.toList()));

        List<CategoryDto> categories = byCategory.entrySet().stream()
            .map(entry -> {
                String cat = entry.getKey();
                List<ModelEntity> list = entry.getValue();
                ModelEntity primary = list.stream()
                    .filter(ModelEntity::isDefault)
                    .findFirst()
                    .orElse(list.getFirst());
                String humanName = formatHumanCategoryName(cat);
                return CategoryDto.of(cat, humanName, primary.getId());
            })
            .toList();

        return new ModelsResponseDto(modelDtos, categories);
    }

    @Transactional(readOnly = true)
    public ProvidersResponseDto getProviders() {
        List<ModelProviderEntity> providers = providerRepository.findAllByOrderByDisplayOrderAscIdAsc();
        List<ProviderDto> dtos = providers.stream()
            .map(p -> ProviderDto.fromEntity(p, chatClientRegistry.isProviderConfigured(p)))
            .toList();
        return new ProvidersResponseDto(dtos);
    }

    public SyncResultDto syncModels(String providerIdFilter) {
        if (providerIdFilter != null && !providerIdFilter.isBlank()) {
            ModelProviderEntity provider = providerRepository.findById(providerIdFilter)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found: " + providerIdFilter));
            int count = discoveryService.syncProvider(provider.getId());
            return SyncResultDto.success(1, count);
        } else {
            return discoveryService.syncAllEnabledProviders();
        }
    }

    @Transactional(readOnly = true)
    public ResolvedChatModel resolveAndValidateChatModel(String requestedModel) {
        return resolutionService.resolveAndValidateChatModel(requestedModel);
    }

    private static String formatHumanCategoryName(String category) {
        if (category == null || category.isBlank()) {
            return "General";
        }
        return switch (category.toLowerCase()) {
            case "grok_4", "grok-4" -> "Grok 4";
            case "grok_2", "grok-2" -> "Grok 2";
            case "claude" -> "Claude";
            case "gpt_4o", "gpt-4o" -> "GPT-4o";
            case "deepseek" -> "DeepSeek";
            case "llama" -> "Llama";
            case "general" -> "General";
            default -> {
                String[] parts = category.replace('-', '_').split("_");
                StringBuilder sb = new StringBuilder();
                for (String part : parts) {
                    if (!part.isBlank()) {
                        if (!sb.isEmpty()) sb.append(" ");
                        sb.append(Character.toUpperCase(part.charAt(0)))
                          .append(part.substring(1).toLowerCase());
                    }
                }
                yield sb.toString();
            }
        };
    }
}
