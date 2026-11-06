package io.modivra.model.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modivra.TestcontainersConfiguration;
import io.modivra.model.dto.DiscoveredModelDto;
import io.modivra.model.dto.SyncResultDto;
import io.modivra.model.entity.ApiMode;
import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;
import io.modivra.model.repository.ModelProviderRepository;
import io.modivra.model.repository.ModelRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Adversarial empirical test suite for ModelDiscoveryService:
 * 1. Single-flight deduplication under high-concurrency virtual thread storm
 * 2. Failure recovery & activeRequest eviction invariant
 * 3. Strict admin mutation preservation on model upsert
 * 4. Safe admin initial defaults on brand new model discovery
 * 5. Provider-scoped slug collision disambiguation
 * 6. Live PostgreSQL 17 transaction & schema validation persistence
 */
class ModelDiscoveryServiceAdversarialTest {

    // =========================================================================
    // 1. SINGLE-FLIGHT DEDUPLICATION & CONCURRENCY ADVERSARIAL TESTS
    // =========================================================================
    @Nested
    @DisplayName("Single-Flight Deduplication & Concurrency")
    class SingleFlightDeduplicationTests {

        @Test
        @DisplayName("50 concurrent virtual threads requesting same provider collapse into exactly 1 upstream discovery call")
        void concurrentRequestsSameProviderCollapseIntoSingleCall() throws Exception {
            ModelDiscoveryAdapter adapter = mock(ModelDiscoveryAdapter.class);
            when(adapter.supports(any(), eq("test-provider"))).thenReturn(true);
            when(adapter.getOrder()).thenReturn(10);

            AtomicInteger callCount = new AtomicInteger(0);
            CountDownLatch readyLatch = new CountDownLatch(50);
            CountDownLatch startLatch = new CountDownLatch(1);

            DiscoveredModelDto model = new DiscoveredModelDto(
                "model-1", "slug-1", "Title 1", "Desc 1",
                128000, 4096, 128000,
                "general", "test-provider", "v1", "fp1",
                List.of("text"), List.of("text"),
                List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now()
            );

            when(adapter.discoverModels(any())).thenAnswer(invocation -> {
                callCount.incrementAndGet();
                // Simulate network latency to ensure threads overlap in-flight
                Thread.sleep(60);
                return List.of(model);
            });

            ModelProviderRepository providerRepo = mock(ModelProviderRepository.class);
            ModelRepository modelRepo = mock(ModelRepository.class);

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapter),
                providerRepo,
                modelRepo
            );

            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("test-provider");
            provider.setApiMode(ApiMode.OPENAI_COMPATIBLE);

            List<CompletableFuture<List<DiscoveredModelDto>>> futures = new ArrayList<>();
            List<List<DiscoveredModelDto>> results = Collections.synchronizedList(new ArrayList<>());

            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 50; i++) {
                    futures.add(CompletableFuture.supplyAsync(() -> {
                        readyLatch.countDown();
                        try {
                            startLatch.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        List<DiscoveredModelDto> res = service.discoverModelsDeduplicated(provider);
                        results.add(res);
                        return res;
                    }, executor));
                }

                assertThat(readyLatch.await(5, TimeUnit.SECONDS)).isTrue();
                // Release all 50 virtual threads simultaneously
                startLatch.countDown();

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            // Invariant: Exactly 1 upstream adapter execution took place
            assertThat(callCount.get())
                .as("Single-flight deduplication must collapse 50 concurrent requests into exactly 1 adapter call")
                .isEqualTo(1);

            // Invariant: All 50 threads received identical discovered models
            assertThat(results).hasSize(50);
            for (List<DiscoveredModelDto> res : results) {
                assertThat(res).hasSize(1);
                assertThat(res.getFirst().modelId()).isEqualTo("model-1");
            }
        }

        @Test
        @DisplayName("Concurrent discovery requests for distinct providers execute independently without blocking each other")
        void concurrentDistinctProvidersExecuteIndependently() {
            ModelDiscoveryAdapter adapterA = mock(ModelDiscoveryAdapter.class);
            when(adapterA.supports(any(), eq("prov-a"))).thenReturn(true);
            when(adapterA.getOrder()).thenReturn(10);

            ModelDiscoveryAdapter adapterB = mock(ModelDiscoveryAdapter.class);
            when(adapterB.supports(any(), eq("prov-b"))).thenReturn(true);
            when(adapterB.getOrder()).thenReturn(10);

            AtomicInteger callsA = new AtomicInteger(0);
            AtomicInteger callsB = new AtomicInteger(0);

            when(adapterA.discoverModels(any())).thenAnswer(invocation -> {
                callsA.incrementAndGet();
                Thread.sleep(40);
                return List.of(new DiscoveredModelDto("mod-a", "a", "Title A", "", 1000, 100, 1000, "g", "prov-a", null, null, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now()));
            });

            when(adapterB.discoverModels(any())).thenAnswer(invocation -> {
                callsB.incrementAndGet();
                Thread.sleep(40);
                return List.of(new DiscoveredModelDto("mod-b", "b", "Title B", "", 1000, 100, 1000, "g", "prov-b", null, null, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now()));
            });

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapterA, adapterB),
                mock(ModelProviderRepository.class),
                mock(ModelRepository.class)
            );

            ModelProviderEntity provA = new ModelProviderEntity();
            provA.setId("prov-a");
            ModelProviderEntity provB = new ModelProviderEntity();
            provB.setId("prov-b");

            List<CompletableFuture<?>> futures = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 20; i++) {
                    futures.add(CompletableFuture.runAsync(() -> service.discoverModelsDeduplicated(provA), executor));
                    futures.add(CompletableFuture.runAsync(() -> service.discoverModelsDeduplicated(provB), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }

            assertThat(callsA.get()).isEqualTo(1);
            assertThat(callsB.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("Upstream failure fails all callers and reliably evicts the active request, allowing subsequent retries")
        void upstreamFailureFailsAllCallersAndEvictsKey() {
            ModelDiscoveryAdapter adapter = mock(ModelDiscoveryAdapter.class);
            when(adapter.supports(any(), eq("failing-prov"))).thenReturn(true);
            when(adapter.getOrder()).thenReturn(10);

            AtomicInteger calls = new AtomicInteger(0);

            when(adapter.discoverModels(any())).thenAnswer(invocation -> {
                int c = calls.incrementAndGet();
                Thread.sleep(30);
                if (c == 1) {
                    throw new IllegalStateException("Upstream 503 Service Unavailable");
                }
                return List.of(new DiscoveredModelDto("recovered-mod", "rec", "Recovered", "", 1000, 100, 1000, "g", "failing-prov", null, null, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now()));
            });

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapter),
                mock(ModelProviderRepository.class),
                mock(ModelRepository.class)
            );

            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("failing-prov");

            // Phase 1: 10 concurrent requests during failure
            List<CompletableFuture<List<DiscoveredModelDto>>> failFutures = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 10; i++) {
                    failFutures.add(CompletableFuture.supplyAsync(
                        () -> service.discoverModelsDeduplicated(provider),
                        executor
                    ));
                }
            }

            for (CompletableFuture<List<DiscoveredModelDto>> f : failFutures) {
                assertThatThrownBy(f::join)
                    .isInstanceOf(CompletionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Upstream 503 Service Unavailable");
            }

            // Only 1 adapter call occurred during the concurrent failure
            assertThat(calls.get()).isEqualTo(1);

            // Phase 2: Key was evicted from activeRequests; a new request must execute fresh call (and succeed)
            List<DiscoveredModelDto> recoveredResult = service.discoverModelsDeduplicated(provider);
            assertThat(recoveredResult).hasSize(1);
            assertThat(recoveredResult.getFirst().modelId()).isEqualTo("recovered-mod");
            assertThat(calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("Adapter returning null is safely normalized to empty list without NullPointerException")
        void adapterReturningNullNormalizedToEmptyList() {
            ModelDiscoveryAdapter adapter = mock(ModelDiscoveryAdapter.class);
            when(adapter.supports(any(), eq("null-prov"))).thenReturn(true);
            when(adapter.getOrder()).thenReturn(10);
            when(adapter.discoverModels(any())).thenReturn(null);

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapter),
                mock(ModelProviderRepository.class),
                mock(ModelRepository.class)
            );

            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("null-prov");

            List<DiscoveredModelDto> result = service.discoverModelsDeduplicated(provider);
            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("findAdapter chooses the adapter with lowest order value when multiple match")
        void findAdapterSelectsLowestOrder() {
            ModelDiscoveryAdapter adapterHigh = mock(ModelDiscoveryAdapter.class);
            when(adapterHigh.supports(any(), eq("multi-prov"))).thenReturn(true);
            when(adapterHigh.getOrder()).thenReturn(100);

            ModelDiscoveryAdapter adapterLow = mock(ModelDiscoveryAdapter.class);
            when(adapterLow.supports(any(), eq("multi-prov"))).thenReturn(true);
            when(adapterLow.getOrder()).thenReturn(10);

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapterHigh, adapterLow),
                mock(ModelProviderRepository.class),
                mock(ModelRepository.class)
            );

            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("multi-prov");

            ModelDiscoveryAdapter chosen = service.findAdapter(provider);
            assertThat(chosen).isSameAs(adapterLow);
        }

        @Test
        @DisplayName("Missing adapter throws IllegalStateException and does not leave activeRequests stuck")
        void missingAdapterThrowsIllegalStateExceptionAndEvicts() {
            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(),
                mock(ModelProviderRepository.class),
                mock(ModelRepository.class)
            );

            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("unknown-prov");

            assertThatThrownBy(() -> service.discoverModelsDeduplicated(provider))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No discovery adapter found");

            // Subsequent call also fails cleanly with same error (not hung waiting on completed future)
            assertThatThrownBy(() -> service.discoverModelsDeduplicated(provider))
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // 2. ADMIN MUTATION PRESERVATION ON MODEL UPSERT
    // =========================================================================
    @Nested
    @DisplayName("Admin Mutation Preservation on Model Upsert")
    class AdminMutationPreservationTests {

        @Test
        @DisplayName("Updating existing model strictly preserves admin fields: is_enabled, is_default, title, description, display_order, slug")
        void existingModelPreservesAllAdminCustomizations() {
            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("xai");

            // Pre-existing model with human admin customizations
            ModelEntity existing = new ModelEntity();
            existing.setId("xai/grok-4.7");
            existing.setProvider(provider);
            existing.setModelId("grok-4.7");
            existing.setSlug("admin-custom-slug");
            existing.setTitle("Curated Human Grok 4.7 Title");
            existing.setDescription("Curated Human Description written by administrator");
            existing.setEnabled(false); // Admin disabled this model!
            existing.setDefault(true);  // Admin designated this as default!
            existing.setDisplayOrder(42); // Custom human display order!
            existing.setContextWindow(8192);
            existing.setMaxOutputTokens(2048);
            existing.setMaxTokens(8192);
            existing.setPricing(Map.of("prompt_text_token_price", 5L));
            existing.setInputModalities(List.of("text"));
            existing.setOutputModalities(List.of("text"));
            existing.setFingerprint("fp_old");
            existing.setModelVersion("v1.0");

            ModelRepository modelRepo = mock(ModelRepository.class);
            when(modelRepo.findById("xai/grok-4.7")).thenReturn(Optional.of(existing));

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(),
                mock(ModelProviderRepository.class),
                modelRepo
            );

            // Upstream discovery sync arrives with updated specs and default upstream titles
            DiscoveredModelDto upstreamDto = new DiscoveredModelDto(
                "grok-4.7",
                "upstream-raw-slug",                 // Should NOT overwrite slug
                "Upstream Default Grok 4.7",         // Should NOT overwrite title
                "Upstream default auto description", // Should NOT overwrite description
                262144,                              // SHOULD update contextWindow
                32768,                               // SHOULD update maxOutputTokens
                262144,                              // SHOULD update maxTokens
                "grok_4", "xai",
                "v2.0-upstream",                     // SHOULD update modelVersion
                "fp_new_999",                        // SHOULD update fingerprint
                List.of("text", "image"),            // SHOULD update inputModalities
                List.of("text"),
                List.of("grok-4.7-alias"),
                List.of("reasoning"),
                Map.of("vision", true),
                Map.of(),
                List.of(),
                Map.of("prompt_text_token_price", 2L), // SHOULD update pricing
                Instant.ofEpochMilli(1700000000000L)
            );

            service.upsertDiscoveredModel(provider, upstreamDto);

            ArgumentCaptor<ModelEntity> captor = ArgumentCaptor.forClass(ModelEntity.class);
            verify(modelRepo).save(captor.capture());
            ModelEntity saved = captor.getValue();

            // 1. VERIFY ADMIN PRESERVATIONS:
            assertThat(saved.isEnabled())
                .as("Admin is_enabled=false must NEVER be overwritten by sync")
                .isFalse();

            assertThat(saved.isDefault())
                .as("Admin is_default=true must NEVER be overwritten by sync")
                .isTrue();

            assertThat(saved.getTitle())
                .as("Admin custom title must NEVER be overwritten by sync")
                .isEqualTo("Curated Human Grok 4.7 Title");

            assertThat(saved.getDescription())
                .as("Admin custom description must NEVER be overwritten by sync")
                .isEqualTo("Curated Human Description written by administrator");

            assertThat(saved.getDisplayOrder())
                .as("Admin custom display_order must NEVER be overwritten by sync")
                .isEqualTo(42);

            assertThat(saved.getSlug())
                .as("Admin custom slug must NEVER be overwritten by sync")
                .isEqualTo("admin-custom-slug");

            // 2. VERIFY TECHNICAL SPEC UPDATES:
            assertThat(saved.getContextWindow()).isEqualTo(262144);
            assertThat(saved.getMaxOutputTokens()).isEqualTo(32768);
            assertThat(saved.getMaxTokens()).isEqualTo(262144);
            assertThat(saved.getFingerprint()).isEqualTo("fp_new_999");
            assertThat(saved.getModelVersion()).isEqualTo("v2.0-upstream");
            assertThat(saved.getInputModalities()).containsExactly("text", "image");
            assertThat(saved.getPricing()).containsEntry("prompt_text_token_price", 2L);
            assertThat(saved.getUpstreamCreatedAt()).isEqualTo(Instant.ofEpochMilli(1700000000000L));
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Newly discovered model is inserted with safe initial admin defaults: is_enabled=true, is_default=false, display_order=100")
        void newDiscoveredModelSetsSafeInitialDefaults() {
            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("openrouter");

            ModelRepository modelRepo = mock(ModelRepository.class);
            when(modelRepo.findById("openrouter/meta/llama-3.3-70b")).thenReturn(Optional.empty());
            when(modelRepo.findByProviderIdAndSlugAndIsEnabledTrue("openrouter", "meta-llama-3.3-70b"))
                .thenReturn(Optional.empty());

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(),
                mock(ModelProviderRepository.class),
                modelRepo
            );

            DiscoveredModelDto newModelDto = new DiscoveredModelDto(
                "meta/llama-3.3-70b",
                "meta-llama-3.3-70b",
                "Llama 3.3 70B Instruct",
                "Meta high-performance open model",
                131072, 8192, 131072,
                "llama", "openrouter",
                null, null,
                List.of("text"), List.of("text"),
                List.of(), List.of("open-weights"), Map.of(), Map.of(), List.of(),
                Map.of("prompt", 0.0007),
                Instant.now()
            );

            service.upsertDiscoveredModel(provider, newModelDto);

            ArgumentCaptor<ModelEntity> captor = ArgumentCaptor.forClass(ModelEntity.class);
            verify(modelRepo).save(captor.capture());
            ModelEntity saved = captor.getValue();

            assertThat(saved.getId()).isEqualTo("openrouter/meta/llama-3.3-70b");
            assertThat(saved.getModelId()).isEqualTo("meta/llama-3.3-70b");
            assertThat(saved.getSlug()).isEqualTo("meta-llama-3.3-70b");
            assertThat(saved.getTitle()).isEqualTo("Llama 3.3 70B Instruct");
            assertThat(saved.getDescription()).isEqualTo("Meta high-performance open model");

            // Safe initial admin defaults
            assertThat(saved.isEnabled())
                .as("New discovered model should be enabled by default")
                .isTrue();
            assertThat(saved.isDefault())
                .as("New discovered model must NEVER be set to default automatically (protects uq_models_single_default)")
                .isFalse();
            assertThat(saved.getDisplayOrder())
                .as("New discovered model should have display_order=100")
                .isEqualTo(100);
            assertThat(saved.getCreatedAt()).isNotNull();
            assertThat(saved.getUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Slug collision within same provider disambiguates slug suffix to avoid uq_models_provider_slug violation")
        void slugCollisionWithinProviderResolvesUniqueSlug() {
            ModelProviderEntity provider = new ModelProviderEntity();
            provider.setId("openrouter");

            ModelEntity existingSameSlug = new ModelEntity();
            existingSameSlug.setId("openrouter/vendor1/llama-3");
            existingSameSlug.setSlug("llama-3");

            ModelRepository modelRepo = mock(ModelRepository.class);
            when(modelRepo.findById("openrouter/vendor2/llama-3")).thenReturn(Optional.empty());
            when(modelRepo.findByProviderIdAndSlugAndIsEnabledTrue("openrouter", "llama-3"))
                .thenReturn(Optional.of(existingSameSlug));

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(),
                mock(ModelProviderRepository.class),
                modelRepo
            );

            DiscoveredModelDto collidingDto = new DiscoveredModelDto(
                "vendor2/llama-3",
                "llama-3",
                "Llama 3 Vendor 2",
                "",
                8192, 2048, 8192,
                "llama", "openrouter",
                null, null,
                List.of("text"), List.of("text"),
                List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(),
                Instant.now()
            );

            service.upsertDiscoveredModel(provider, collidingDto);

            ArgumentCaptor<ModelEntity> captor = ArgumentCaptor.forClass(ModelEntity.class);
            verify(modelRepo).save(captor.capture());
            ModelEntity saved = captor.getValue();

            // Disambiguated slug must not be raw "llama-3"
            assertThat(saved.getSlug()).isNotEqualTo("llama-3");
            assertThat(saved.getSlug()).startsWith("llama-3-");
        }

        @Test
        @DisplayName("syncProvider skips execution when provider is disabled or does not support discovery")
        void syncProviderSkipsDisabledOrUnsupportedProvider() {
            ModelProviderEntity disabledProv = new ModelProviderEntity();
            disabledProv.setId("disabled-p");
            disabledProv.setEnabled(false);
            disabledProv.setSupportsDiscovery(true);

            ModelProviderEntity noDiscProv = new ModelProviderEntity();
            noDiscProv.setId("no-disc-p");
            noDiscProv.setEnabled(true);
            noDiscProv.setSupportsDiscovery(false);

            ModelProviderRepository providerRepo = mock(ModelProviderRepository.class);
            when(providerRepo.findById("disabled-p")).thenReturn(Optional.of(disabledProv));
            when(providerRepo.findById("no-disc-p")).thenReturn(Optional.of(noDiscProv));

            ModelDiscoveryAdapter adapter = mock(ModelDiscoveryAdapter.class);

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapter),
                providerRepo,
                mock(ModelRepository.class)
            );

            assertThat(service.syncProvider("disabled-p")).isEqualTo(0);
            assertThat(service.syncProvider("no-disc-p")).isEqualTo(0);

            verify(adapter, never()).discoverModels(any());
        }

        @Test
        @DisplayName("syncAllEnabledProviders isolates individual provider failures and aggregates successful counts")
        void syncAllEnabledProvidersIsolatesFailures() {
            ModelProviderEntity provSuccess = new ModelProviderEntity();
            provSuccess.setId("prov-ok");
            provSuccess.setEnabled(true);
            provSuccess.setSupportsDiscovery(true);

            ModelProviderEntity provFail = new ModelProviderEntity();
            provFail.setId("prov-bad");
            provFail.setEnabled(true);
            provFail.setSupportsDiscovery(true);

            ModelProviderRepository providerRepo = mock(ModelProviderRepository.class);
            when(providerRepo.findAllByIsEnabledTrueOrderByDisplayOrderAsc())
                .thenReturn(List.of(provFail, provSuccess));
            when(providerRepo.findById("prov-bad")).thenReturn(Optional.of(provFail));
            when(providerRepo.findById("prov-ok")).thenReturn(Optional.of(provSuccess));

            ModelDiscoveryAdapter adapterBad = mock(ModelDiscoveryAdapter.class);
            when(adapterBad.supports(any(), eq("prov-bad"))).thenReturn(true);
            when(adapterBad.getOrder()).thenReturn(10);
            when(adapterBad.discoverModels(any())).thenThrow(new RuntimeException("Simulated Network Timeout"));

            ModelDiscoveryAdapter adapterOk = mock(ModelDiscoveryAdapter.class);
            when(adapterOk.supports(any(), eq("prov-ok"))).thenReturn(true);
            when(adapterOk.getOrder()).thenReturn(10);
            when(adapterOk.discoverModels(any())).thenReturn(List.of(
                new DiscoveredModelDto("ok-1", "ok-1", "OK 1", "", 1000, 100, 1000, "g", "prov-ok", null, null, List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of(), List.of(), Map.of(), Instant.now())
            ));

            ModelRepository modelRepo = mock(ModelRepository.class);
            when(modelRepo.findById(any())).thenReturn(Optional.empty());

            ModelDiscoveryService service = new ModelDiscoveryService(
                List.of(adapterBad, adapterOk),
                providerRepo,
                modelRepo
            );

            SyncResultDto result = service.syncAllEnabledProviders();

            // prov-bad failed, but prov-ok succeeded with 1 model upserted
            assertThat(result.providersSynced()).isEqualTo(1);
            assertThat(result.modelsUpserted()).isEqualTo(1);
        }
    }

    // =========================================================================
    // 3. DATABASE INTEGRATION ADVERSARIAL TESTS (PostgreSQL 17 Testcontainers)
    // =========================================================================
    @Nested
    @DisplayName("Database Integration: Live PostgreSQL 17 Flyway V2 Schema")
    @Import(TestcontainersConfiguration.class)
    @SpringBootTest
    @TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=validate"
    })
    class DatabaseIntegrationAdversarialTests {

        @Autowired
        private ModelDiscoveryService discoveryService;

        @Autowired
        private ModelRepository modelRepository;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @Test
        @DisplayName("Live DB: syncProvider('xai') succeeds without duplicate key violation on grok-2 (model_id='grok-2-latest')")
        void syncProviderXAiSucceedsWithoutDuplicateKeyViolation() {
            int upserted = discoveryService.syncProvider("xai");
            assertThat(upserted).isGreaterThan(0);

            Optional<ModelEntity> grok2Opt = modelRepository.findById("xai/grok-2");
            assertThat(grok2Opt).isPresent();
            ModelEntity grok2 = grok2Opt.get();
            assertThat(grok2.getModelId()).isEqualTo("grok-2-latest");
            assertThat(grok2.getSlug()).isEqualTo("grok-2");
            assertThat(grok2.isDefault()).isFalse();
        }

        @Test
        @DisplayName("Live DB: syncProvider preserves human administrative changes on openrouter")
        void liveDatabaseAdminPreservationOnOpenRouter() {
            String testCanonicalId = "openrouter/anthropic/claude-3.5-sonnet";

            // Step 1: Simulate human admin customizing openrouter model
            String adminTitle = "Custom Human Admin Sonnet 3.5";
            String adminDescription = "Custom Human Admin Description for Sonnet 3.5";
            int adminDisplayOrder = 17;
            boolean adminEnabled = false; // Admin explicitly turned it off!

            jdbcTemplate.update("""
                UPDATE models
                SET title = ?,
                    description = ?,
                    display_order = ?,
                    is_enabled = ?
                WHERE id = ?
                """, adminTitle, adminDescription, adminDisplayOrder, adminEnabled, testCanonicalId);

            // Step 2: Trigger discovery sync for openrouter
            int upserted = discoveryService.syncProvider("openrouter");
            assertThat(upserted).isGreaterThan(0);

            // Step 3: Query directly from PostgreSQL via JdbcTemplate
            Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT title, description, display_order, is_enabled, is_default, context_window, max_output_tokens FROM models WHERE id = ?",
                testCanonicalId
            );

            assertThat(row.get("title"))
                .as("Admin custom title must be preserved in PostgreSQL database table")
                .isEqualTo(adminTitle);

            assertThat(row.get("description"))
                .as("Admin custom description must be preserved in PostgreSQL database table")
                .isEqualTo(adminDescription);

            assertThat(row.get("display_order"))
                .as("Admin custom display_order must be preserved in PostgreSQL database table")
                .isEqualTo(adminDisplayOrder);

            assertThat(row.get("is_enabled"))
                .as("Admin custom is_enabled must be preserved in PostgreSQL database table")
                .isEqualTo(adminEnabled);

            assertThat(row.get("is_default"))
                .as("is_default must remain false as established in V2 migration seed")
                .isEqualTo(false);

            assertThat((Integer) row.get("context_window")).isGreaterThan(0);
            assertThat((Integer) row.get("max_output_tokens")).isGreaterThan(0);

            // Step 4: Restore baseline state
            jdbcTemplate.update("""
                UPDATE models
                SET title = 'Claude 3.5 Sonnet (OpenRouter)',
                    description = 'Anthropic flagship reasoning model via OpenRouter',
                    display_order = 40,
                    is_enabled = TRUE
                WHERE id = ?
                """, testCanonicalId);
        }

        @Test
        @DisplayName("Concurrent syncProvider calls on same provider are single-flight deduplicated without optimistic locking collision")
        void liveDatabaseConcurrentSyncProviderCallsAreDeduplicatedWithoutCollision() {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 15; i++) {
                    futures.add(CompletableFuture.supplyAsync(
                        () -> discoveryService.syncProvider("openrouter"),
                        executor
                    ));
                }

                // Invariant: All concurrent callers complete successfully without StaleStateException
                for (CompletableFuture<Integer> f : futures) {
                    assertThat(f.join()).isGreaterThan(0);
                }
            }
        }
    }
}
