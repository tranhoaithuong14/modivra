package io.modivra.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modivra.TestcontainersConfiguration;
import io.modivra.conversation.ConversationDtos.ContentDto;
import io.modivra.conversation.ConversationEntity;
import io.modivra.conversation.ConversationNodeEntity;
import io.modivra.conversation.ConversationNodeRepository;
import io.modivra.conversation.ConversationRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Adversarial empirical test harness verifying database schema invariants
 * introduced in V2__create_model_registry_schema.sql.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=validate"
})
class SchemaInvariantsAdversarialTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationNodeRepository nodeRepository;

    // =========================================================================
    // INVARIANT 1: Single Default Invariant (`uq_models_single_default`)
    // =========================================================================
    @Nested
    @DisplayName("Invariant 1: Single Default Model (`uq_models_single_default`)")
    class SingleDefaultInvariantTests {

        @Test
        @DisplayName("Baseline seed contains exactly one default model: xai/grok-4.7")
        void baselineContainsExactlyOneDefaultModel() {
            Integer defaultCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM models WHERE is_default = TRUE",
                Integer.class
            );
            assertThat(defaultCount).isEqualTo(1);

            String defaultModelId = jdbcTemplate.queryForObject(
                "SELECT id FROM models WHERE is_default = TRUE",
                String.class
            );
            assertThat(defaultModelId).isEqualTo("xai/grok-4.7");
        }

        @Test
        @DisplayName("Inserting a second default model triggers unique constraint violation on uq_models_single_default")
        void insertingSecondDefaultModelFails() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO models (
                        id, provider_id, model_id, slug, title, is_default
                    ) VALUES (
                        'openrouter/extra-default', 'openrouter', 'extra-default', 'extra-default', 'Extra Default', TRUE
                    )
                    """);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("uq_models_single_default");
            });
        }

        @Test
        @DisplayName("Updating an existing non-default model to default while another exists triggers uq_models_single_default")
        void updatingNonDefaultToDefaultWhileDefaultExistsFails() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("UPDATE models SET is_default = TRUE WHERE id = 'xai/grok-2'");
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("uq_models_single_default");
            });
        }

        @Test
        @DisplayName("Multiple non-default models (is_default = FALSE) can coexist without violation")
        void multipleNonDefaultModelsCanCoexist() {
            try {
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('xai/test-non-default-1', 'xai', 'test-nd-1', 'test-nd-1', 'Test ND 1', FALSE)
                    """);
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('xai/test-non-default-2', 'xai', 'test-nd-2', 'test-nd-2', 'Test ND 2', FALSE)
                    """);

                Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE is_default = FALSE AND id LIKE 'xai/test-non-default%'",
                    Integer.class
                );
                assertThat(count).isEqualTo(2);
            } finally {
                jdbcTemplate.update("DELETE FROM models WHERE id LIKE 'xai/test-non-default%'");
            }
        }

        @Test
        @DisplayName("Default model can be transferred if previous default is cleared first")
        void defaultModelCanBeTransferredSequentially() {
            try {
                // Clear default on xai/grok-4.7
                jdbcTemplate.update("UPDATE models SET is_default = FALSE WHERE id = 'xai/grok-4.7'");

                // Set default on xai/grok-2
                int rowsUpdated = jdbcTemplate.update("UPDATE models SET is_default = TRUE WHERE id = 'xai/grok-2'");
                assertThat(rowsUpdated).isEqualTo(1);

                String currentDefault = jdbcTemplate.queryForObject(
                    "SELECT id FROM models WHERE is_default = TRUE",
                    String.class
                );
                assertThat(currentDefault).isEqualTo("xai/grok-2");
            } finally {
                // Restore original state
                jdbcTemplate.update("UPDATE models SET is_default = FALSE WHERE id = 'xai/grok-2'");
                jdbcTemplate.update("UPDATE models SET is_default = TRUE WHERE id = 'xai/grok-4.7'");
            }
        }
    }

    // =========================================================================
    // INVARIANT 2: Provider-Scoped Slug Invariant (`uq_models_provider_slug`)
    // =========================================================================
    @Nested
    @DisplayName("Invariant 2: Provider-Scoped Slug Invariant (`uq_models_provider_slug`)")
    class ProviderScopedSlugInvariantTests {

        @Test
        @DisplayName("Two different providers CAN share identical slugs without collision")
        void differentProvidersCanShareIdenticalSlug() {
            String sharedSlug = "common-chat-model";
            try {
                // Provider 1: openai
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('openai/common-chat-model', 'openai', 'chat-model-raw', ?, 'OpenAI Model', FALSE)
                    """, sharedSlug);

                // Provider 2: openrouter
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('openrouter/common-chat-model', 'openrouter', 'router-chat-model-raw', ?, 'Router Model', FALSE)
                    """, sharedSlug);

                Integer countWithSharedSlug = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE slug = ?",
                    Integer.class,
                    sharedSlug
                );
                assertThat(countWithSharedSlug).isEqualTo(2);
            } finally {
                jdbcTemplate.update("DELETE FROM models WHERE id IN ('openai/common-chat-model', 'openrouter/common-chat-model')");
            }
        }

        @Test
        @DisplayName("Same provider inserting duplicate slug triggers uq_models_provider_slug violation")
        void sameProviderInsertingDuplicateSlugFails() {
            try {
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('xai/slug-test-1', 'xai', 'raw-id-1', 'duplicate-scoped-slug', 'Test 1', FALSE)
                    """);

                assertThatThrownBy(() -> {
                    jdbcTemplate.update("""
                        INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                        VALUES ('xai/slug-test-2', 'xai', 'raw-id-2', 'duplicate-scoped-slug', 'Test 2', FALSE)
                        """);
                })
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                    assertThat(cause.getMessage()).containsIgnoringCase("uq_models_provider_slug");
                });
            } finally {
                jdbcTemplate.update("DELETE FROM models WHERE id IN ('xai/slug-test-1', 'xai/slug-test-2')");
            }
        }

        @Test
        @DisplayName("Updating a model to have a colliding slug within same provider triggers uq_models_provider_slug")
        void updatingModelToCollidingSlugWithinSameProviderFails() {
            try {
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('xai/slug-update-target', 'xai', 'raw-target', 'unique-slug-target', 'Target', FALSE)
                    """);

                // grok-4.7 already exists in xai provider. Attempting to change slug to grok-4.7 must fail
                assertThatThrownBy(() -> {
                    jdbcTemplate.update("""
                        UPDATE models SET slug = 'grok-4.7' WHERE id = 'xai/slug-update-target'
                        """);
                })
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                    assertThat(cause.getMessage()).containsIgnoringCase("uq_models_provider_slug");
                });
            } finally {
                jdbcTemplate.update("DELETE FROM models WHERE id = 'xai/slug-update-target'");
            }
        }

        @Test
        @DisplayName("Same provider inserting duplicate model_id triggers uq_models_provider_model_id")
        void sameProviderInsertingDuplicateModelIdFails() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('xai/another-grok-47', 'xai', 'grok-4.7', 'different-slug', 'Duplicate Raw Model Id', FALSE)
                    """);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("uq_models_provider_model_id");
            });
        }
    }

    // =========================================================================
    // INVARIANT 3: Column Widening Invariant (VARCHAR(192))
    // =========================================================================
    @Nested
    @DisplayName("Invariant 3: Column Widening Invariant (VARCHAR(192))")
    class ColumnWideningInvariantTests {

        private final String LONG_SLUG_36 = "openrouter/anthropic/claude-3.5-sonnet";
        private final String EXACT_192_SLUG = "openrouter/" + "a".repeat(192 - "openrouter/".length());
        private final String OVERFLOW_193_SLUG = "openrouter/" + "a".repeat(193 - "openrouter/".length());

        @Test
        @DisplayName("Database column character_maximum_length is exactly 192 for both widened columns")
        void verifyDatabaseColumnMetadataLength() {
            Integer convModelSlugLen = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'conversations' AND column_name = 'default_model_slug'
                """, Integer.class);
            assertThat(convModelSlugLen).isEqualTo(192);

            Integer nodeModelSlugLen = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'conversation_nodes' AND column_name = 'model_slug'
                """, Integer.class);
            assertThat(nodeModelSlugLen).isEqualTo(192);
        }

        @Test
        @DisplayName("conversations.default_model_slug accepts representative long multi-vendor slug (36 chars)")
        void conversationsAcceptsRepresentative36CharSlug() {
            UUID convId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Long Slug Test', ?, TRUE, FALSE)
                    """, convId, LONG_SLUG_36);

                String savedSlug = jdbcTemplate.queryForObject(
                    "SELECT default_model_slug FROM conversations WHERE id = ?",
                    String.class,
                    convId
                );
                assertThat(savedSlug).isEqualTo(LONG_SLUG_36);
            } finally {
                jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            }
        }

        @Test
        @DisplayName("conversations.default_model_slug accepts boundary string of exactly 192 characters")
        void conversationsAcceptsExact192CharSlug() {
            assertThat(EXACT_192_SLUG).hasSize(192);
            UUID convId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Exact 192 Slug Test', ?, TRUE, FALSE)
                    """, convId, EXACT_192_SLUG);

                String savedSlug = jdbcTemplate.queryForObject(
                    "SELECT default_model_slug FROM conversations WHERE id = ?",
                    String.class,
                    convId
                );
                assertThat(savedSlug).isEqualTo(EXACT_192_SLUG);
                assertThat(savedSlug.length()).isEqualTo(192);
            } finally {
                jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            }
        }

        @Test
        @DisplayName("conversations.default_model_slug rejects 193-character string with value too long error")
        void conversationsRejects193CharSlug() {
            assertThat(OVERFLOW_193_SLUG).hasSize(193);
            UUID convId = UUID.randomUUID();
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Overflow Slug Test', ?, TRUE, FALSE)
                    """, convId, OVERFLOW_193_SLUG);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                if (cause instanceof SQLException sqlEx) {
                    // PostgreSQL SQLState 22001: string_data_right_truncation (value too long)
                    assertThat(sqlEx.getSQLState()).isEqualTo("22001");
                }
                assertThat(cause.getMessage()).containsIgnoringCase("value too long for type character varying(192)");
            });
        }

        @Test
        @DisplayName("conversation_nodes.model_slug accepts representative long multi-vendor slug (36 chars)")
        void conversationNodesAcceptsRepresentative36CharSlug() {
            UUID convId = UUID.randomUUID();
            UUID rootId = UUID.randomUUID();
            UUID nodeId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Node Test', 'grok-4.7', TRUE, FALSE)
                    """, convId);

                // Root node (parent_id is null, role is null, content is null)
                jdbcTemplate.update("""
                    INSERT INTO conversation_nodes (id, conversation_id, parent_id, role, content, model_slug, status, metadata)
                    VALUES (?, ?, NULL, NULL, NULL, NULL, 'finished_successfully', '{}'::jsonb)
                    """, rootId, convId);

                // Child assistant node with model_slug
                jdbcTemplate.update("""
                    INSERT INTO conversation_nodes (
                        id, conversation_id, parent_id, role, content, model_slug, status, metadata
                    ) VALUES (
                        ?, ?, ?, 'assistant', '{"content_type":"text","parts":["Node with long slug"]}'::jsonb, ?, 'finished_successfully', '{}'::jsonb
                    )
                    """, nodeId, convId, rootId, LONG_SLUG_36);

                String savedSlug = jdbcTemplate.queryForObject(
                    "SELECT model_slug FROM conversation_nodes WHERE id = ?",
                    String.class,
                    nodeId
                );
                assertThat(savedSlug).isEqualTo(LONG_SLUG_36);
            } finally {
                jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            }
        }

        @Test
        @DisplayName("conversation_nodes.model_slug accepts boundary string of exactly 192 characters")
        void conversationNodesAcceptsExact192CharSlug() {
            assertThat(EXACT_192_SLUG).hasSize(192);
            UUID convId = UUID.randomUUID();
            UUID rootId = UUID.randomUUID();
            UUID nodeId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Node Exact 192 Test', 'grok-4.7', TRUE, FALSE)
                    """, convId);

                // Root node
                jdbcTemplate.update("""
                    INSERT INTO conversation_nodes (id, conversation_id, parent_id, role, content, model_slug, status, metadata)
                    VALUES (?, ?, NULL, NULL, NULL, NULL, 'finished_successfully', '{}'::jsonb)
                    """, rootId, convId);

                // Child assistant node with exact 192-char slug
                jdbcTemplate.update("""
                    INSERT INTO conversation_nodes (
                        id, conversation_id, parent_id, role, content, model_slug, status, metadata
                    ) VALUES (
                        ?, ?, ?, 'assistant', '{"content_type":"text","parts":["Node with exact 192 slug"]}'::jsonb, ?, 'finished_successfully', '{}'::jsonb
                    )
                    """, nodeId, convId, rootId, EXACT_192_SLUG);

                String savedSlug = jdbcTemplate.queryForObject(
                    "SELECT model_slug FROM conversation_nodes WHERE id = ?",
                    String.class,
                    nodeId
                );
                assertThat(savedSlug).isEqualTo(EXACT_192_SLUG);
                assertThat(savedSlug.length()).isEqualTo(192);
            } finally {
                jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            }
        }

        @Test
        @DisplayName("conversation_nodes.model_slug rejects 193-character string with value too long error")
        void conversationNodesRejects193CharSlug() {
            assertThat(OVERFLOW_193_SLUG).hasSize(193);
            UUID convId = UUID.randomUUID();
            UUID rootId = UUID.randomUUID();
            UUID nodeId = UUID.randomUUID();
            try {
                jdbcTemplate.update("""
                    INSERT INTO conversations (id, user_id, title, default_model_slug, is_visible, is_archived)
                    VALUES (?, 'test-user', 'Node Overflow Test', 'grok-4.7', TRUE, FALSE)
                    """, convId);

                // Root node
                jdbcTemplate.update("""
                    INSERT INTO conversation_nodes (id, conversation_id, parent_id, role, content, model_slug, status, metadata)
                    VALUES (?, ?, NULL, NULL, NULL, NULL, 'finished_successfully', '{}'::jsonb)
                    """, rootId, convId);

                assertThatThrownBy(() -> {
                    jdbcTemplate.update("""
                        INSERT INTO conversation_nodes (
                            id, conversation_id, parent_id, role, content, model_slug, status, metadata
                        ) VALUES (
                            ?, ?, ?, 'assistant', '{"content_type":"text","parts":["Overflow"]}'::jsonb, ?, 'finished_successfully', '{}'::jsonb
                        )
                        """, nodeId, convId, rootId, OVERFLOW_193_SLUG);
                })
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(ex -> {
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                    if (cause instanceof SQLException sqlEx) {
                        assertThat(sqlEx.getSQLState()).isEqualTo("22001");
                    }
                    assertThat(cause.getMessage()).containsIgnoringCase("value too long for type character varying(192)");
                });
            } finally {
                jdbcTemplate.update("DELETE FROM conversations WHERE id = ?", convId);
            }
        }

        @Test
        @DisplayName("JPA Hibernate Repository persists ConversationEntity and ConversationNodeEntity with 192-char slugs")
        void jpaRepositoryPersistsEntitiesWith192CharSlugs() {
            Instant now = Instant.now();
            UUID convId = UUID.randomUUID();
            ConversationEntity conversation = new ConversationEntity();
            conversation.setId(convId);
            conversation.setUserId("jpa-test-user");
            conversation.setTitle("JPA 192 Slug Test");
            conversation.setDefaultModelSlug(EXACT_192_SLUG);
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversation = conversationRepository.saveAndFlush(conversation);

            // 1. Root Node (parent=null, role=null, content=null)
            UUID rootNodeId = UUID.randomUUID();
            ConversationNodeEntity rootNode = new ConversationNodeEntity();
            rootNode.setId(rootNodeId);
            rootNode.setConversationId(convId);
            rootNode.setParent(null);
            rootNode.setRole(null);
            rootNode.setContent(null);
            rootNode.setModelSlug(null);
            rootNode.setStatus("finished_successfully");
            rootNode.setCreatedAt(now);
            rootNode.setUpdatedAt(now);
            rootNode = nodeRepository.saveAndFlush(rootNode);

            // 2. Child Assistant Node (parent=rootNode, role="assistant", modelSlug=192 chars)
            UUID childNodeId = UUID.randomUUID();
            ConversationNodeEntity childNode = new ConversationNodeEntity();
            childNode.setId(childNodeId);
            childNode.setConversationId(convId);
            childNode.setParent(rootNode);
            childNode.setRole("assistant");
            childNode.setContent(ContentDto.text("JPA exact 192 content"));
            childNode.setModelSlug(EXACT_192_SLUG);
            childNode.setStatus("finished_successfully");
            childNode.setCreatedAt(now);
            childNode.setUpdatedAt(now);
            childNode = nodeRepository.saveAndFlush(childNode);

            ConversationEntity reloadedConv = conversationRepository.findById(conversation.getId()).orElseThrow();
            assertThat(reloadedConv.getDefaultModelSlug()).isEqualTo(EXACT_192_SLUG);
            assertThat(reloadedConv.getDefaultModelSlug().length()).isEqualTo(192);

            ConversationNodeEntity reloadedNode = nodeRepository.findById(childNode.getId()).orElseThrow();
            assertThat(reloadedNode.getModelSlug()).isEqualTo(EXACT_192_SLUG);
            assertThat(reloadedNode.getModelSlug().length()).isEqualTo(192);

            // Clean up
            nodeRepository.delete(childNode);
            nodeRepository.delete(rootNode);
            conversationRepository.delete(conversation);
        }
    }

    // =========================================================================
    // ADDITIONAL INVARIANTS: Referential Integrity & Domain Check Constraints
    // =========================================================================
    @Nested
    @DisplayName("Additional Invariants: Foreign Keys & Domain Constraints")
    class AdditionalInvariantsTests {

        @Test
        @DisplayName("Foreign key enforces provider existence: inserting model with non-existent provider fails")
        void modelRequiresValidProviderId() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('invalid-provider/m1', 'non_existent_provider', 'm1', 'm1', 'Model 1', FALSE)
                    """);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("models_provider_id_fkey");
            });
        }

        @Test
        @DisplayName("Foreign key ON DELETE CASCADE removes associated models when provider is deleted")
        void deletingProviderCascadesToModels() {
            try {
                jdbcTemplate.update("""
                    INSERT INTO model_providers (id, display_name, api_mode, base_url)
                    VALUES ('temp-provider', 'Temp Provider', 'openai_compatible', 'http://localhost:8000')
                    """);
                jdbcTemplate.update("""
                    INSERT INTO models (id, provider_id, model_id, slug, title, is_default)
                    VALUES ('temp-provider/m1', 'temp-provider', 'm1', 'm1', 'Model 1', FALSE)
                    """);

                Integer modelCountBefore = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE provider_id = 'temp-provider'",
                    Integer.class
                );
                assertThat(modelCountBefore).isEqualTo(1);

                jdbcTemplate.update("DELETE FROM model_providers WHERE id = 'temp-provider'");

                Integer modelCountAfter = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE provider_id = 'temp-provider'",
                    Integer.class
                );
                assertThat(modelCountAfter).isEqualTo(0);
            } finally {
                jdbcTemplate.update("DELETE FROM model_providers WHERE id = 'temp-provider'");
            }
        }

        @Test
        @DisplayName("Check constraint chk_models_limits rejects non-positive token limits")
        void checkConstraintRejectsNonPositiveTokenLimits() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO models (
                        id, provider_id, model_id, slug, title, context_window, max_output_tokens, max_tokens, is_default
                    ) VALUES (
                        'xai/bad-limits', 'xai', 'bad-limits', 'bad-limits', 'Bad Limits', -1, 1000, 1000, FALSE
                    )
                    """);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("chk_models_limits");
            });
        }

        @Test
        @DisplayName("Check constraint chk_model_providers_api_mode rejects invalid api modes")
        void checkConstraintRejectsInvalidApiMode() {
            assertThatThrownBy(() -> {
                jdbcTemplate.update("""
                    INSERT INTO model_providers (id, display_name, api_mode, base_url)
                    VALUES ('bad-mode-provider', 'Bad Mode', 'unsupported_mode', 'http://localhost:8000')
                    """);
            })
            .isInstanceOf(DataIntegrityViolationException.class)
            .satisfies(ex -> {
                Throwable cause = NestedExceptionUtils.getMostSpecificCause(ex);
                assertThat(cause.getMessage()).containsIgnoringCase("chk_model_providers_api_mode");
            });
        }
    }
}
