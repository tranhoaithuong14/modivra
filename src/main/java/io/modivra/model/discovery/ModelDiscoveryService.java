package io.modivra.model.discovery;

import io.modivra.model.dto.DiscoveredModelDto;
import io.modivra.model.dto.SyncResultDto;
import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;
import io.modivra.model.repository.ModelProviderRepository;
import io.modivra.model.repository.ModelRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ModelDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ModelDiscoveryService.class);

    private final List<ModelDiscoveryAdapter> adapters;
    private final ModelProviderRepository providerRepository;
    private final ModelRepository modelRepository;

    private final ConcurrentMap<String, CompletableFuture<List<DiscoveredModelDto>>> activeRequests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<SyncResultDto>> inFlightSyncs = new ConcurrentHashMap<>();

    public ModelDiscoveryService(
        List<ModelDiscoveryAdapter> adapters,
        ModelProviderRepository providerRepository,
        ModelRepository modelRepository
    ) {
        this.adapters = adapters;
        this.providerRepository = providerRepository;
        this.modelRepository = modelRepository;
    }

    /**
     * Executes single-flight deduplicated discovery for the specified provider.
     * Prevents thundering herd spikes when multiple requests arrive concurrently.
     */
    public List<DiscoveredModelDto> discoverModelsDeduplicated(ModelProviderEntity provider) {
        String providerId = provider.getId();

        return activeRequests.computeIfAbsent(providerId, id -> {
            CompletableFuture<List<DiscoveredModelDto>> future = new CompletableFuture<>();
            Thread.ofVirtual().name("discovery-" + id).start(() -> {
                try {
                    ModelDiscoveryAdapter adapter = findAdapter(provider);
                    List<DiscoveredModelDto> discovered = adapter.discoverModels(provider);
                    future.complete(discovered != null ? discovered : List.of());
                } catch (Throwable t) {
                    log.error("Model discovery failed for provider {}: {}", id, t.getMessage(), t);
                    future.completeExceptionally(t);
                } finally {
                    activeRequests.remove(id);
                }
            });
            return future;
        }).join();
    }

    public List<DiscoveredModelDto> discoverModels(ModelProviderEntity provider) {
        return discoverModelsDeduplicated(provider);
    }

    public ModelDiscoveryAdapter findAdapter(ModelProviderEntity provider) {
        return adapters.stream()
            .filter(a -> a.supports(provider.getApiMode(), provider.getId()))
            .min(Comparator.comparingInt(ModelDiscoveryAdapter::getOrder))
            .orElseThrow(() -> new IllegalStateException("No discovery adapter found for provider: " + provider.getId()));
    }

    /**
     * Synchronizes a single provider by ID with single-flight deduplication and admin mutation protection.
     * Prevents optimistic lock collisions (@Version) by collapsing concurrent sync calls into a single execution.
     */
    public int syncProvider(String providerId) {
        return syncProviderDetailed(providerId).modelsUpserted();
    }

    /**
     * Executes single-flight deduplicated sync returning full SyncResultDto.
     */
    public SyncResultDto syncProviderDetailed(String providerId) {
        ModelProviderEntity provider = providerRepository.findById(providerId)
            .orElseThrow(() -> new IllegalArgumentException("Provider not found: " + providerId));

        if (!provider.isEnabled() || !provider.isSupportsDiscovery()) {
            return SyncResultDto.success(0, 0);
        }

        return inFlightSyncs.computeIfAbsent(providerId, id -> {
            CompletableFuture<SyncResultDto> future = new CompletableFuture<>();
            Thread.ofVirtual().name("sync-" + id).start(() -> {
                try {
                    List<DiscoveredModelDto> discoveredList = discoverModelsDeduplicated(provider);
                    int upserted = 0;
                    for (DiscoveredModelDto dto : discoveredList) {
                        upsertDiscoveredModel(provider, dto);
                        upserted++;
                    }
                    future.complete(SyncResultDto.success(1, upserted));
                } catch (Throwable t) {
                    log.error("Model sync failed for provider {}: {}", id, t.getMessage(), t);
                    future.completeExceptionally(t);
                } finally {
                    inFlightSyncs.remove(id);
                }
            });
            return future;
        }).join();
    }

    /**
     * Synchronizes all enabled providers supporting discovery with error isolation.
     */
    public SyncResultDto syncAllEnabledProviders() {
        List<ModelProviderEntity> providers = providerRepository.findAllByIsEnabledTrueOrderByDisplayOrderAsc();
        int providersSynced = 0;
        int modelsUpserted = 0;

        for (ModelProviderEntity provider : providers) {
            if (!provider.isSupportsDiscovery()) {
                continue;
            }
            try {
                int count = syncProvider(provider.getId());
                providersSynced++;
                modelsUpserted += count;
            } catch (Exception ex) {
                log.error("Sync failed for provider {}: {}", provider.getId(), ex.getMessage());
            }
        }

        return SyncResultDto.success(providersSynced, modelsUpserted);
    }

    /**
     * Upsert discovered model while STRICTLY preserving admin customizations:
     * - is_enabled
     * - is_default
     * - title
     * - description
     * - display_order
     * Only technical metadata and limits are updated on existing entities.
     */
    @Transactional
    public void upsertDiscoveredModel(ModelProviderEntity provider, DiscoveredModelDto dto) {
        String canonicalId = provider.getId() + "/" + dto.modelId();
        Optional<ModelEntity> existingOpt = modelRepository.findByProviderIdAndModelId(provider.getId(), dto.modelId())
            .or(() -> modelRepository.findById(canonicalId));

        if (existingOpt.isPresent()) {
            ModelEntity existing = existingOpt.get();

            // 1. UPDATE TECHNICAL METADATA ONLY:
            existing.setContextWindow(dto.contextWindow());
            existing.setMaxOutputTokens(dto.maxOutputTokens());
            existing.setMaxTokens(dto.maxTokens());
            existing.setInputModalities(dto.inputModalities());
            existing.setOutputModalities(dto.outputModalities());
            existing.setPricing(dto.pricing());
            if (dto.fingerprint() != null) {
                existing.setFingerprint(dto.fingerprint());
            }
            if (dto.modelVersion() != null) {
                existing.setModelVersion(dto.modelVersion());
            }
            if (dto.upstreamCreatedAt() != null) {
                existing.setUpstreamCreatedAt(dto.upstreamCreatedAt());
            }
            existing.setUpdatedAt(Instant.now());

            // 2. STRICTLY PRESERVE ADMIN CUSTOMIZATIONS:
            // NEVER touch existing.isEnabled(), existing.isDefault(), existing.getTitle(),
            // existing.getDescription(), existing.getDisplayOrder(), existing.getSlug()

            modelRepository.save(existing);
        } else {
            // New model discovery: insert with safe initial defaults
            ModelEntity entity = new ModelEntity();
            entity.setId(canonicalId);
            entity.setProvider(provider);
            entity.setModelId(dto.modelId());

            // Ensure provider-scoped unique slug
            String slug = resolveUniqueSlug(provider.getId(), dto.slug(), dto.modelId());
            entity.setSlug(slug);

            entity.setTitle(dto.title());
            entity.setDescription(dto.description());
            entity.setContextWindow(dto.contextWindow());
            entity.setMaxOutputTokens(dto.maxOutputTokens());
            entity.setMaxTokens(dto.maxTokens());
            entity.setCategory(dto.category());
            entity.setOwnedBy(dto.ownedBy());
            entity.setModelVersion(dto.modelVersion());
            entity.setFingerprint(dto.fingerprint());
            entity.setInputModalities(dto.inputModalities());
            entity.setOutputModalities(dto.outputModalities());
            entity.setAliases(dto.aliases());
            entity.setTags(dto.tags());
            entity.setCapabilities(dto.capabilities());
            entity.setProductFeatures(dto.productFeatures());
            entity.setEnabledTools(dto.enabledTools());
            entity.setPricing(dto.pricing());
            entity.setUpstreamCreatedAt(dto.upstreamCreatedAt());

            // Admin defaults for new models
            entity.setEnabled(true);
            entity.setDefault(false); // Discovered model is NEVER default automatically
            entity.setDisplayOrder(100);
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());

            modelRepository.save(entity);
        }
    }

    private String resolveUniqueSlug(String providerId, String candidateSlug, String modelId) {
        String baseSlug = (candidateSlug != null && !candidateSlug.isBlank()) ? candidateSlug : modelId;
        Optional<ModelEntity> existing = modelRepository.findByProviderIdAndSlug(providerId, baseSlug)
            .or(() -> modelRepository.findByProviderIdAndSlugAndIsEnabledTrue(providerId, baseSlug));
        if (existing.isEmpty()) {
            return baseSlug;
        }
        // Collision within the same provider: disambiguate using vendor or hash
        return baseSlug + "-" + (Math.abs(modelId.hashCode()) % 1000);
    }
}
