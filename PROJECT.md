# Project: Modivra Slice 2A — Multi-Provider Model Registry, Discovery & Dynamic Routing

## Architecture
- **Language & Runtime**: Java 25, Spring Boot 4.1.2, Spring AI 2.0.1, PostgreSQL 17, Flyway 10.x, Testcontainers.
- **Package `io.modivra.model`**:
  - `entity`: `ModelProviderEntity`, `ModelEntity`, `ApiMode` (enum)
  - `repository`: `ModelProviderRepository`, `ModelRepository`
  - `dto`: ChatGPT Web compatible DTOs (`ModelsResponseDto`, `ModelItemDto`, `CategoryDto`, `ProviderDto`, `SyncResultDto`)
  - `catalog`: `BuiltinModelSpecCatalog`, `ModelSpec`
  - `discovery`: `ModelDiscoveryAdapter` (SPI), `XAiDiscoveryAdapter`, `OpenRouterDiscoveryAdapter`, `GenericOpenAiDiscoveryAdapter`, `OllamaDiscoveryAdapter`, `ModelDiscoveryService` (with single-flight deduplication & admin preservation)
  - `routing`: `MultiProviderChatClientRegistry`, `ResolvedChatModel`
  - `service`: `ModelRegistryService`, `ModelResolutionService`
  - `controller`: `ModelController` (`GET /api/v1/models`, `POST /api/v1/models/sync`, `GET /api/v1/providers`)
  - `exception`: `ModelNotFoundException`, `ModelDisabledException`, `ModelAmbiguityException`
- **Package `io.modivra.conversation`**:
  - `ConversationEntity`, `ConversationNodeEntity`: update column lengths to 192
  - `ConversationTreeService`: integrate `ModelResolutionService` in `prepareTurn`
  - `ConversationService`: route stream and title generation via `MultiProviderChatClientRegistry` with isolated error handling

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| F01 | Column Widening DDL | Widen `conversation_nodes.model_slug` and `conversations.default_model_slug` to `VARCHAR(192)` | M1 | ORIGINAL_REQUEST R1 |
| F02 | `model_providers` Table | Relational table for provider endpoints, api_mode, api_key_env, supports_discovery | M1 | ORIGINAL_REQUEST R1 |
| F03 | `models` Table | Relational table for model limits, modalities, pricing, JSONB aliases, is_default | M1 | ORIGINAL_REQUEST R1 |
| F04 | Single Default Model Constraint | Partial unique index `uq_models_single_default` on `models (is_default) WHERE is_default = TRUE` | M1 | ORIGINAL_REQUEST R1 |
| F05 | Provider-Scoped Unique Slugs | Composite unique constraint `uq_models_provider_slug UNIQUE (provider_id, slug)` | M1 | ORIGINAL_REQUEST R1 |
| F06 | JSONB Aliases & GIN Index | `aliases JSONB` with `idx_models_aliases_gin` GIN index | M1 | ORIGINAL_REQUEST R1 |
| F07 | Baseline Seed Data | Seed baseline providers (xai, openrouter, ollama, openai) and models (`xai/grok-4.7` default, `xai/grok-2` with `model_id='grok-2-latest'`) | M1 | ORIGINAL_REQUEST R1 |
| F08 | Conversation Entity Widening | Update `@Column(length = 192)` in `ConversationEntity` and `ConversationNodeEntity` | M1 | ORIGINAL_REQUEST R1, Codebase Survey |
| F09 | Model Domain Entities & Enums | `ModelProviderEntity`, `ModelEntity`, `ApiMode` with JPA mapping | M2 | ORIGINAL_REQUEST R2 |
| F10 | Model & Provider Repositories | Spring Data JPA repositories with JSONB querying and provider slug lookups | M2 | ORIGINAL_REQUEST R2 |
| F11 | ChatGPT Web DTOs | DTOs matching `{ "models": [...], "categories": [...] }` for `GET /api/v1/models` | M2 | ORIGINAL_REQUEST R2 |
| F12 | BuiltinModelSpecCatalog | Offline baseline catalog supplying fallback specs when upstream is incomplete or offline | M2 | ORIGINAL_REQUEST R2 |
| F13 | ModelDiscoveryAdapter SPI | SPI interface `supports(apiMode, providerId)` and `discoverModels(provider)` | M2 | ORIGINAL_REQUEST R2 |
| F14 | Discovery Adapters Implementation | Adapters for xAI, OpenRouter, generic OpenAI-compatible, and Ollama | M2 | ORIGINAL_REQUEST R2 |
| F15 | Single-Flight Deduplication | `ConcurrentHashMap<String, CompletableFuture<List<DiscoveredModelDto>>>` to prevent upstream thundering herd | M2 | ORIGINAL_REQUEST R2, Research |
| F16 | Admin Mutation Preservation | Upsert logic preserving admin-configured `is_enabled`, `title`, `description`, `display_order` | M2 | ORIGINAL_REQUEST R2, Research |
| F17 | MultiProviderChatClientRegistry | Dynamic `ChatClient` registry caching clients per provider, delegating to `TestChatModel` under test conditions | M2 | ORIGINAL_REQUEST R2, R3 |
| F18 | Model Registry & Discovery REST Endpoints | `GET /api/v1/models`, `POST /api/v1/models/sync`, `GET /api/v1/providers` in `ModelController` | M2 | ORIGINAL_REQUEST R2 |
| F19 | 5-Stage Model Resolution Ladder | Stage 0 (default fallback on null/blank), slash validation, disabled check, Stage 1 (canonical ID), Stage 2 (provider+slug), Stage 3 (provider+alias), Stage 4 (global alias), Stage 5 (short slug with display_order priority) | M3 | ORIGINAL_REQUEST R3 |
| F20 | Fail-Fast Exceptions | `ModelDisabledException` (HTTP 400), `ModelAmbiguityException` (HTTP 400), `ModelNotFoundException` (HTTP 400) | M3 | ORIGINAL_REQUEST R3 |
| F21 | Conversation Turn Resolution Integration | `ConversationTreeService.prepareTurn` resolves model and records canonical ID in assistant node | M3 | ORIGINAL_REQUEST R3 |
| F22 | Dynamic Stream Routing | `ConversationService.streamPreparedTurn` uses resolved provider `ChatClient` and `upstreamModelId` | M3 | ORIGINAL_REQUEST R3 |
| F23 | Safe Title Generation Isolation | Title generation executed in try-catch on virtual thread with fallback, never crashing SSE stream | M3 | ORIGINAL_REQUEST R3 |
| F24 | Slice 1 Wire & Test Compatibility | Wire request sends `grok-2-latest` when using `xai/grok-2`, all 16 existing tests pass unchanged | M3 | ORIGINAL_REQUEST R3, R4 |
| F25 | Hermetic Test Suite | `ModelRegistryIntegrationTest.java` running on Testcontainers PostgreSQL 17 with mocks/stubs | M4 | ORIGINAL_REQUEST R4 |
| F26 | Full Regression Clean Pass | All 16 existing tests and new integration tests pass cleanly via `./mvnw clean test` | M4 | ORIGINAL_REQUEST R4 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Database Schema & Migration | Flyway `V2__create_model_registry_schema.sql`, column widening to 192, seed data, update entity lengths | none | DONE |
| 2 | Model Registry Domain, Catalog & Discovery Adapters | `io.modivra.model` entities, repos, DTOs, BuiltinModelSpecCatalog, ModelDiscoveryAdapter SPI & 4 implementations, single-flight deduplication, admin preservation, MultiProviderChatClientRegistry, REST endpoints | M1 | IN_PROGRESS |
| 3 | Conversation Integration, Model Resolution & Dynamic Routing | 5-stage resolution ladder, exceptions, `ConversationTreeService` and `ConversationService` integration, isolated title generation, backward compatibility preservation | M1, M2 | PLANNED |
| 4 | Hermetic Verification & E2E Testing | `ModelRegistryIntegrationTest.java` testing ChatGPT Web API, discovery sync, resolution ladder, dynamic routing, and full 16-test regression suite | M1, M2, M3 | PLANNED |

## Interface Contracts
### `io.modivra.model` ↔ `io.modivra.conversation`
- Record `ResolvedChatModel`:
  ```java
  public record ResolvedChatModel(
      String canonicalId,       // e.g. "xai/grok-4.7", "openrouter/anthropic/claude-3-opus"
      String upstreamModelId,   // e.g. "grok-4.7", "anthropic/claude-3-opus", "grok-2-latest"
      ModelProviderEntity providerEntity,
      ModelEntity modelEntity
  ) {}
  ```
- Method `ModelResolutionService.resolveAndValidateChatModel(String requestedModel)`:
  - Returns `ResolvedChatModel`.
  - Throws `ModelDisabledException`, `ModelAmbiguityException`, `ModelNotFoundException` (all mapped to HTTP 400 Bad Request).
- Method `MultiProviderChatClientRegistry.getChatClient(ModelProviderEntity provider)`:
  - Returns `ChatClient` configured for the provider.
  - If `primaryChatModel instanceof TestChatModel`, delegates to primary `ChatClient` for `xai`.

## Code Layout
- `src/main/resources/db/migration/V2__create_model_registry_schema.sql`
- `src/main/java/io/modivra/conversation/ConversationEntity.java` (length = 192)
- `src/main/java/io/modivra/conversation/ConversationNodeEntity.java` (length = 192)
- `src/main/java/io/modivra/conversation/ConversationTreeService.java`
- `src/main/java/io/modivra/conversation/ConversationService.java`
- `src/main/java/io/modivra/model/`
  - `entity/ModelProviderEntity.java`
  - `entity/ModelEntity.java`
  - `entity/ApiMode.java`
  - `repository/ModelProviderRepository.java`
  - `repository/ModelRepository.java`
  - `dto/ModelsResponseDto.java`
  - `dto/ModelItemDto.java`
  - `dto/CategoryDto.java`
  - `dto/ProviderDto.java`
  - `dto/SyncResultDto.java`
  - `catalog/BuiltinModelSpecCatalog.java`
  - `catalog/ModelSpec.java`
  - `discovery/ModelDiscoveryAdapter.java`
  - `discovery/XAiDiscoveryAdapter.java`
  - `discovery/OpenRouterDiscoveryAdapter.java`
  - `discovery/GenericOpenAiDiscoveryAdapter.java`
  - `discovery/OllamaDiscoveryAdapter.java`
  - `discovery/ModelDiscoveryService.java`
  - `routing/MultiProviderChatClientRegistry.java`
  - `routing/ResolvedChatModel.java`
  - `service/ModelRegistryService.java`
  - `service/ModelResolutionService.java`
  - `controller/ModelController.java`
  - `exception/ModelNotFoundException.java`
  - `exception/ModelDisabledException.java`
  - `exception/ModelAmbiguityException.java`
- `src/test/java/io/modivra/model/ModelRegistryIntegrationTest.java`
