package io.modivra.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modivra.TestChatModel;
import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationEntity;
import io.modivra.conversation.ConversationNodeEntity;
import io.modivra.conversation.ConversationNodeRepository;
import io.modivra.conversation.ConversationRepository;
import io.modivra.model.entity.ApiMode;
import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;
import io.modivra.model.repository.ModelProviderRepository;
import io.modivra.model.repository.ModelRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ModelRegistryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestChatModel testChatModel;

    @Autowired
    private ModelRepository modelRepository;

    @Autowired
    private ModelProviderRepository providerRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    @BeforeEach
    void setUp() {
        testChatModel.reset();
        conversationRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /api/v1/models satisfies ChatGPT Web specification")
    void shouldExposeModelsMatchingChatGptWebSpecification() throws Exception {
        mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.models", hasSize(greaterThanOrEqualTo(4))))
            .andExpect(jsonPath("$.models[?(@.id == 'xai/grok-4.7')].object").value(hasItem("model")))
            .andExpect(jsonPath("$.models[?(@.id == 'xai/grok-4.7')].owned_by").value(hasItem("xai")))
            .andExpect(jsonPath("$.models[?(@.id == 'xai/grok-4.7')].created").isNotEmpty())
            .andExpect(jsonPath("$.models[?(@.id == 'xai/grok-4.7')].slug").value(hasItem("grok-4.7")))
            .andExpect(jsonPath("$.models[?(@.id == 'xai/grok-4.7')].is_default").value(hasItem(true)))
            .andExpect(jsonPath("$.models[?(@.id == 'openrouter/anthropic/claude-3.5-sonnet')].category").value(hasItem("claude")))
            .andExpect(jsonPath("$.categories").isArray())
            .andExpect(jsonPath("$.categories[?(@.category == 'grok_4')].human_category_name").value(hasItem("Grok 4")))
            .andExpect(jsonPath("$.categories[?(@.category == 'grok_2')].human_category_name").value(hasItem("Grok 2")))
            .andExpect(jsonPath("$.categories[?(@.category == 'claude')].human_category_name").value(hasItem("Claude")));
    }

    @Test
    @DisplayName("GET /api/v1/providers returns provider list ordered by display_order")
    void shouldExposeProvidersWithConfiguredStatus() throws Exception {
        mockMvc.perform(get("/api/v1/providers").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.providers", hasSize(greaterThanOrEqualTo(4))))
            .andExpect(jsonPath("$.providers[0].id").value("xai"))
            .andExpect(jsonPath("$.providers[0].display_name").value("xAI (Grok)"))
            .andExpect(jsonPath("$.providers[0].api_mode").value("openai_compatible"))
            .andExpect(jsonPath("$.providers[0].is_enabled").value(true))
            .andExpect(jsonPath("$.providers[?(@.id == 'ollama')].configured").value(hasItem(true)));
    }

    @Test
    @DisplayName("Stage 0: Default model resolution when model parameter is omitted or empty")
    void shouldResolveDefaultModelWhenNoneProvided() throws Exception {
        MvcResult initResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Hello without model"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initResult))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(finalResult.getResponse().getContentAsString()).contains("[DONE]");

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        assertThat(conv.getDefaultModelSlug()).isEqualTo("xai/grok-4.7");

        List<ConversationNodeEntity> nodes = nodeRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId());
        ConversationNodeEntity assistantNode = nodes.stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getModelSlug()).isEqualTo("xai/grok-4.7");
        assertThat(assistantNode.getMetadata()).containsEntry("model_slug", "xai/grok-4.7");
        assertThat(assistantNode.getMetadata()).containsEntry("upstream_model_id", "grok-4.7");
    }

    @Test
    @DisplayName("Stage 1 & 2: Canonical ID resolution and wire upstream model mapping")
    void shouldResolveCanonicalIdExactMatch() throws Exception {
        MvcResult initResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "xai/grok-2",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Test canonical xai/grok-2"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initResult))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(finalResult.getResponse().getContentAsString()).contains("[DONE]");

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        List<ConversationNodeEntity> nodes = nodeRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId());
        ConversationNodeEntity assistantNode = nodes.stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getModelSlug()).isEqualTo("xai/grok-2");
        assertThat(assistantNode.getMetadata()).containsEntry("model_slug", "xai/grok-2");
        assertThat(assistantNode.getMetadata()).containsEntry("upstream_model_id", "grok-2-latest");
    }

    @Test
    @DisplayName("Stage 5: Short slug resolution across providers (claude-3.5-sonnet -> openrouter/anthropic/claude-3.5-sonnet)")
    void shouldResolveByShortSlug() throws Exception {
        MvcResult initResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "claude-3.5-sonnet",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Test short slug"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initResult))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(finalResult.getResponse().getContentAsString()).contains("[DONE]");

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        assertThat(conv.getDefaultModelSlug()).isEqualTo("openrouter/anthropic/claude-3.5-sonnet");
    }

    @Test
    @DisplayName("Stage 3: Provider-scoped alias resolution (xai/latest -> xai/grok-4.7)")
    void shouldResolveByProviderAlias() throws Exception {
        MvcResult initResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "xai/latest",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Test provider alias"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finalResult = mockMvc.perform(asyncDispatch(initResult))
            .andExpect(status().isOk())
            .andReturn();

        assertThat(finalResult.getResponse().getContentAsString()).contains("[DONE]");

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        assertThat(conv.getDefaultModelSlug()).isEqualTo("xai/grok-4.7");
    }

    @Test
    @DisplayName("Preserve multi-vendor slash structure (openrouter/anthropic/claude-3.5-sonnet)")
    void shouldPreserveMultiVendorSlugStructure() throws Exception {
        MvcResult initResult = mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "openrouter/anthropic/claude-3.5-sonnet",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Multi-vendor test"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(request().asyncStarted())
            .andReturn();

        mockMvc.perform(asyncDispatch(initResult)).andExpect(status().isOk());

        ConversationEntity conv = conversationRepository.findAll().getFirst();
        assertThat(conv.getDefaultModelSlug()).isEqualTo("openrouter/anthropic/claude-3.5-sonnet");

        ConversationNodeEntity assistantNode = nodeRepository.findByConversationIdOrderByCreatedAtAscIdAsc(conv.getId())
            .stream()
            .filter(n -> "assistant".equals(n.getRole()))
            .findFirst()
            .orElseThrow();

        assertThat(assistantNode.getModelSlug()).isEqualTo("openrouter/anthropic/claude-3.5-sonnet");
        assertThat(assistantNode.getMetadata()).containsEntry("upstream_model_id", "anthropic/claude-3.5-sonnet");
    }

    @Test
    @DisplayName("Fail-fast with HTTP 400 when provider or model is disabled")
    void shouldFailFastWithModelDisabledExceptionWhenProviderOrModelDisabled() throws Exception {
        // 1. Test disabled provider using isolated entity
        ModelProviderEntity disabledProv = new ModelProviderEntity();
        disabledProv.setId("test-disabled-prov");
        disabledProv.setDisplayName("Disabled Test Provider");
        disabledProv.setApiMode(ApiMode.OPENAI_COMPATIBLE);
        disabledProv.setBaseUrl("http://localhost:9999/v1");
        disabledProv.setEnabled(false);
        disabledProv.setSupportsDiscovery(false);
        disabledProv.setDisplayOrder(99);
        disabledProv.setCreatedAt(Instant.now());
        disabledProv.setUpdatedAt(Instant.now());
        providerRepository.saveAndFlush(disabledProv);

        ModelEntity modelUnderDisabledProv = new ModelEntity();
        modelUnderDisabledProv.setId("test-disabled-prov/m1");
        modelUnderDisabledProv.setProvider(disabledProv);
        modelUnderDisabledProv.setModelId("m1");
        modelUnderDisabledProv.setSlug("m1");
        modelUnderDisabledProv.setTitle("Model 1");
        modelUnderDisabledProv.setCategory("general");
        modelUnderDisabledProv.setOutputModalities(List.of("text"));
        modelUnderDisabledProv.setEnabled(true);
        modelUnderDisabledProv.setDefault(false);
        modelUnderDisabledProv.setDisplayOrder(1);
        modelUnderDisabledProv.setCreatedAt(Instant.now());
        modelUnderDisabledProv.setUpdatedAt(Instant.now());
        modelRepository.saveAndFlush(modelUnderDisabledProv);

        try {
            mockMvc.perform(
                    post("/api/v1/conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                            {
                              "action": "next",
                              "model": "test-disabled-prov/m1",
                              "messages": [
                                {
                                  "author": { "role": "user" },
                                  "content": { "content_type": "text", "parts": ["Test disabled provider"] }
                                }
                              ]
                            }
                            """)
                )
                .andExpect(status().isBadRequest());
        } finally {
            modelRepository.deleteById("test-disabled-prov/m1");
            providerRepository.deleteById("test-disabled-prov");
        }

        // 2. Test disabled model under enabled provider (ollama)
        ModelProviderEntity ollama = providerRepository.findById("ollama").orElseThrow();
        ModelEntity disabledModel = new ModelEntity();
        disabledModel.setId("ollama/test-disabled-m");
        disabledModel.setProvider(ollama);
        disabledModel.setModelId("test-disabled-m");
        disabledModel.setSlug("test-disabled-slug");
        disabledModel.setTitle("Disabled Test Model");
        disabledModel.setCategory("general");
        disabledModel.setOutputModalities(List.of("text"));
        disabledModel.setEnabled(false);
        disabledModel.setDefault(false);
        disabledModel.setDisplayOrder(1);
        disabledModel.setCreatedAt(Instant.now());
        disabledModel.setUpdatedAt(Instant.now());
        modelRepository.saveAndFlush(disabledModel);

        try {
            mockMvc.perform(
                    post("/api/v1/conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                            {
                              "action": "next",
                              "model": "ollama/test-disabled-m",
                              "messages": [
                                {
                                  "author": { "role": "user" },
                                  "content": { "content_type": "text", "parts": ["Test disabled model"] }
                                }
                              ]
                            }
                            """)
                )
                .andExpect(status().isBadRequest());
        } finally {
            modelRepository.deleteById("ollama/test-disabled-m");
        }
    }

    @Test
    @DisplayName("Fail-fast with HTTP 400 when model identifier is ambiguous across equal priority providers")
    void shouldFailFastWithModelAmbiguityExceptionOnTiedPriority() throws Exception {
        ModelProviderEntity mockProv = new ModelProviderEntity();
        mockProv.setId("mockprov");
        mockProv.setDisplayName("Mock Provider");
        mockProv.setApiMode(ApiMode.OPENAI_COMPATIBLE);
        mockProv.setBaseUrl("http://localhost:9999/v1");
        mockProv.setEnabled(true);
        mockProv.setSupportsDiscovery(false);
        mockProv.setDisplayOrder(10); // Same priority as xAI (10)
        mockProv.setCreatedAt(Instant.now());
        mockProv.setUpdatedAt(Instant.now());
        providerRepository.saveAndFlush(mockProv);

        ModelEntity clash = new ModelEntity();
        clash.setId("mockprov/grok-4.7");
        clash.setProvider(mockProv);
        clash.setModelId("grok-4.7");
        clash.setSlug("grok-4.7"); // Matches xai/grok-4.7's slug ("grok-4.7")
        clash.setTitle("Mock Grok");
        clash.setCategory("grok_4");
        clash.setOutputModalities(List.of("text"));
        clash.setEnabled(true);
        clash.setDefault(false);
        clash.setDisplayOrder(10); // Equal display_order
        clash.setCreatedAt(Instant.now());
        clash.setUpdatedAt(Instant.now());
        modelRepository.saveAndFlush(clash);

        try {
            mockMvc.perform(
                    post("/api/v1/conversation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                            {
                              "action": "next",
                              "model": "grok-4.7",
                              "messages": [
                                {
                                  "author": { "role": "user" },
                                  "content": { "content_type": "text", "parts": ["Ambiguous slug test"] }
                                }
                              ]
                            }
                            """)
                )
                .andExpect(status().isBadRequest());
        } finally {
            modelRepository.deleteById("mockprov/grok-4.7");
            providerRepository.deleteById("mockprov");
        }
    }

    @Test
    @DisplayName("Fail-fast with HTTP 400 on malformed slash format or unknown model identifier")
    void shouldFailFastWithModelNotFoundExceptionOnUnknownOrMalformedModel() throws Exception {
        // Unknown model
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "completely-unknown-model-slug",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Unknown model"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(status().isBadRequest());

        // Malformed leading slash
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "/grok-2",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Malformed slash"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(status().isBadRequest());

        // Malformed trailing slash
        mockMvc.perform(
                post("/api/v1/conversation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .content("""
                        {
                          "action": "next",
                          "model": "xai/",
                          "messages": [
                            {
                              "author": { "role": "user" },
                              "content": { "content_type": "text", "parts": ["Malformed slash"] }
                            }
                          ]
                        }
                        """)
            )
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/models/sync returns 200 OK")
    void shouldTriggerSyncEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/models/sync?provider=ollama").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.providers_synced").value(1));
    }
}
