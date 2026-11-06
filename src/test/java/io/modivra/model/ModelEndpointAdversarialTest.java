package io.modivra.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.modivra.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ModelEndpointAdversarialTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Nested
    @DisplayName("Endpoint: GET /api/v1/models Schema & Conformance")
    class GetModelsSchemaTests {

        @Test
        @DisplayName("1. Returns HTTP 200 with ChatGPT Web top-level envelope { models: [...], categories: [...] }")
        void returnsEnvelopeWithModelsAndCategories() throws Exception {
            mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.models").isArray())
                .andExpect(jsonPath("$.categories").isArray());
        }

        @Test
        @DisplayName("2. Verifies required ChatGPT model item fields: id, object = 'model', created, owned_by, capabilities, context_window, max_tokens")
        void verifiesRequiredModelItemFields() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode modelsNode = root.path("models");

            assertThat(modelsNode.isArray()).isTrue();
            assertThat(modelsNode.size()).isGreaterThan(0);

            List<String> missingFieldsReport = new ArrayList<>();

            for (JsonNode modelNode : modelsNode) {
                String modelId = modelNode.path("id").asText();

                // 1. id
                if (!modelNode.hasNonNull("id")) {
                    missingFieldsReport.add("Model '" + modelId + "' missing field 'id'");
                }

                // 2. object = "model"
                if (!modelNode.has("object")) {
                    missingFieldsReport.add("Model '" + modelId + "' missing field 'object'");
                } else if (!"model".equals(modelNode.path("object").asText())) {
                    missingFieldsReport.add("Model '" + modelId + "' field 'object' is '" + modelNode.path("object").asText() + "' instead of 'model'");
                }

                // 3. created
                if (!modelNode.has("created")) {
                    missingFieldsReport.add("Model '" + modelId + "' missing field 'created'");
                }

                // 4. owned_by
                if (!modelNode.hasNonNull("owned_by")) {
                    missingFieldsReport.add("Model '" + modelId + "' missing field 'owned_by'");
                }

                // 5. capabilities
                if (!modelNode.hasNonNull("capabilities") || !modelNode.path("capabilities").isObject()) {
                    missingFieldsReport.add("Model '" + modelId + "' missing or invalid 'capabilities'");
                }

                // 6. context_window
                if (!modelNode.hasNonNull("context_window") || modelNode.path("context_window").asInt() <= 0) {
                    missingFieldsReport.add("Model '" + modelId + "' missing or invalid 'context_window'");
                }

                // 7. max_tokens
                if (!modelNode.hasNonNull("max_tokens") || modelNode.path("max_tokens").asInt() <= 0) {
                    missingFieldsReport.add("Model '" + modelId + "' missing or invalid 'max_tokens'");
                }
            }

            // Systematically assert the finding:
            assertThat(missingFieldsReport)
                .as("ChatGPT Web specification required fields check (id, object='model', created, owned_by, capabilities, context_window, max_tokens)")
                .isEmpty();
        }

        @Test
        @DisplayName("3. Verifies category structure in GET /api/v1/models")
        void verifiesCategoryStructure() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode categories = root.path("categories");

            assertThat(categories.isArray()).isTrue();
            assertThat(categories.size()).isGreaterThan(0);

            for (JsonNode cat : categories) {
                assertThat(cat.hasNonNull("category")).isTrue();
                assertThat(cat.hasNonNull("human_category_name")).isTrue();
                assertThat(cat.hasNonNull("subscription_level")).isTrue();
                assertThat(cat.hasNonNull("default_model")).isTrue();
                assertThat(cat.hasNonNull("browsing_model")).isTrue();
                assertThat(cat.hasNonNull("code_interpreter_model")).isTrue();
                assertThat(cat.hasNonNull("plugins_model")).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Endpoint: GET /api/v1/models Filtering & Omission Invariants")
    class GetModelsOmissionTests {

        @Test
        @DisplayName("4. Disabled models are strictly omitted from GET /api/v1/models")
        void disabledModelsAreOmitted() throws Exception {
            // Disable xai/grok-2
            jdbcTemplate.update("UPDATE models SET is_enabled = FALSE WHERE id = 'xai/grok-2'");

            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode modelsNode = root.path("models");

            for (JsonNode m : modelsNode) {
                assertThat(m.path("id").asText())
                    .withFailMessage("Disabled model 'xai/grok-2' was returned in GET /api/v1/models")
                    .isNotEqualTo("xai/grok-2");
            }
        }

        @Test
        @DisplayName("5. Models belonging to disabled providers are strictly omitted from GET /api/v1/models")
        void disabledProviderModelsAreOmitted() throws Exception {
            // In baseline seed, openai provider is_enabled = FALSE, but openai/gpt-4o is seeded with is_enabled = TRUE.
            // In baseline seed, ollama provider is_enabled = FALSE, but ollama/deepseek-r1:7b is seeded with is_enabled = TRUE.
            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode modelsNode = root.path("models");

            for (JsonNode m : modelsNode) {
                String id = m.path("id").asText();
                String providerId = m.path("provider_id").asText();

                assertThat(id)
                    .withFailMessage("Model '%s' under disabled provider 'openai' was returned in GET /api/v1/models", id)
                    .isNotEqualTo("openai/gpt-4o");
                assertThat(id)
                    .withFailMessage("Model '%s' under disabled provider 'ollama' was returned in GET /api/v1/models", id)
                    .isNotEqualTo("ollama/deepseek-r1:7b");

                assertThat(providerId)
                    .withFailMessage("Model '%s' from disabled provider '%s' was returned", id, providerId)
                    .isNotIn("openai", "ollama");
            }

            // Also dynamically disable xai provider and verify all xai models disappear
            jdbcTemplate.update("UPDATE model_providers SET is_enabled = FALSE WHERE id = 'xai'");

            MvcResult resultAfterXaiDisabled = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode rootAfter = objectMapper.readTree(resultAfterXaiDisabled.getResponse().getContentAsString());
            JsonNode modelsAfter = rootAfter.path("models");

            for (JsonNode m : modelsAfter) {
                assertThat(m.path("provider_id").asText())
                    .withFailMessage("Model from newly disabled provider 'xai' was returned")
                    .isNotEqualTo("xai");
            }
        }

        @Test
        @DisplayName("6. Non-text output models are strictly omitted from GET /api/v1/models")
        void nonTextOutputModelsAreOmitted() throws Exception {
            // Insert an image-only model under xai
            jdbcTemplate.update("""
                INSERT INTO models (
                    id, provider_id, model_id, slug, title, is_enabled, output_modalities
                ) VALUES (
                    'xai/aurora-vision', 'xai', 'aurora-vision', 'aurora-vision', 'Aurora Vision', TRUE, '["image"]'::jsonb
                )
                """);

            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode modelsNode = root.path("models");

            for (JsonNode m : modelsNode) {
                assertThat(m.path("id").asText())
                    .withFailMessage("Non-text output model 'xai/aurora-vision' was returned in GET /api/v1/models")
                    .isNotEqualTo("xai/aurora-vision");
            }
        }

        @Test
        @DisplayName("7. When all models are disabled, returns empty models and categories without throwing 500")
        void allModelsDisabledReturnsEmptyGracefully() throws Exception {
            jdbcTemplate.update("UPDATE models SET is_enabled = FALSE");

            MvcResult result = mockMvc.perform(get("/api/v1/models").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(root.path("models").isArray()).isTrue();
            assertThat(root.path("models").size()).isEqualTo(0);
            assertThat(root.path("categories").isArray()).isTrue();
            assertThat(root.path("categories").size()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Endpoint: GET /api/v1/providers Conformance")
    class GetProvidersTests {

        @Test
        @DisplayName("8. Returns list of providers with required fields: id, display_name, api_mode, is_enabled, supports_discovery")
        void returnsProvidersWithRequiredFields() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/providers").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());

            JsonNode providersNode = root.has("providers") ? root.path("providers") : root;
            assertThat(providersNode.isArray()).isTrue();
            assertThat(providersNode.size()).isGreaterThanOrEqualTo(4);

            for (JsonNode provider : providersNode) {
                String providerId = provider.path("id").asText();

                assertThat(provider.hasNonNull("id"))
                    .withFailMessage("Provider is missing 'id'")
                    .isTrue();

                assertThat(provider.hasNonNull("display_name"))
                    .withFailMessage("Provider '%s' is missing 'display_name'", providerId)
                    .isTrue();

                assertThat(provider.hasNonNull("api_mode"))
                    .withFailMessage("Provider '%s' is missing 'api_mode'", providerId)
                    .isTrue();

                assertThat(provider.hasNonNull("is_enabled"))
                    .withFailMessage("Provider '%s' is missing 'is_enabled'", providerId)
                    .isTrue();

                assertThat(provider.hasNonNull("supports_discovery"))
                    .withFailMessage("Provider '%s' is missing 'supports_discovery'", providerId)
                    .isTrue();
            }

            // Verify specific seeded providers
            assertThat(findProvider(providersNode, "xai")).isNotNull();
            assertThat(findProvider(providersNode, "openrouter")).isNotNull();
            assertThat(findProvider(providersNode, "openai")).isNotNull();
            assertThat(findProvider(providersNode, "ollama")).isNotNull();

            JsonNode xai = findProvider(providersNode, "xai");
            assertThat(xai.path("is_enabled").asBoolean()).isTrue();
            assertThat(xai.path("supports_discovery").asBoolean()).isTrue();

            JsonNode openai = findProvider(providersNode, "openai");
            assertThat(openai.path("is_enabled").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("9. Providers are ordered by display_order ASC, id ASC")
        void providersAreOrderedCorrectly() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/providers").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();

            JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode providersNode = root.path("providers");

            List<String> providerIds = new ArrayList<>();
            for (JsonNode p : providersNode) {
                providerIds.add(p.path("id").asText());
            }

            // Expected order by display_order: xai (10), openrouter (20), openai (30), ollama (40)
            assertThat(providerIds).containsExactly("xai", "openrouter", "openai", "ollama");
        }

        private JsonNode findProvider(JsonNode providersNode, String id) {
            for (JsonNode p : providersNode) {
                if (id.equals(p.path("id").asText())) {
                    return p;
                }
            }
            return null;
        }
    }
}
