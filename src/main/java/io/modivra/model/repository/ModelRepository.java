package io.modivra.model.repository;

import io.modivra.model.entity.ModelEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ModelRepository extends JpaRepository<ModelEntity, String> {

    Optional<ModelEntity> findByIdAndIsEnabledTrue(String id);

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.id = :id
    """)
    Optional<ModelEntity> findByIdWithProvider(@Param("id") String id);

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.isDefault = true
    """)
    Optional<ModelEntity> findByIsDefaultTrue();

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.isEnabled = true AND p.isEnabled = true
        ORDER BY p.displayOrder ASC, m.displayOrder ASC, m.id ASC
    """)
    List<ModelEntity> findActiveCatalogModels();

    default List<ModelEntity> findByIsEnabledTrueAndProvider_IsEnabledTrueOrderByDisplayOrderAscIdAsc() {
        return findActiveCatalogModels();
    }

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE p.id = :providerId
          AND m.slug = :slug
          AND m.isEnabled = true
    """)
    Optional<ModelEntity> findByProviderIdAndSlugAndIsEnabledTrue(
        @Param("providerId") String providerId,
        @Param("slug") String slug
    );

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE p.id = :providerId
          AND m.slug = :slug
    """)
    Optional<ModelEntity> findByProviderIdAndSlug(
        @Param("providerId") String providerId,
        @Param("slug") String slug
    );

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE p.id = :providerId
          AND m.modelId = :modelId
    """)
    Optional<ModelEntity> findByProviderIdAndModelId(
        @Param("providerId") String providerId,
        @Param("modelId") String modelId
    );

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.slug = :slug
          AND m.isEnabled = true
          AND p.isEnabled = true
        ORDER BY p.displayOrder ASC, m.displayOrder ASC, m.id ASC
    """)
    List<ModelEntity> findBySlugAndIsEnabledTrue(
        @Param("slug") String slug
    );

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.slug = :slug
        ORDER BY p.displayOrder ASC, m.displayOrder ASC, m.id ASC
    """)
    List<ModelEntity> findAllBySlug(@Param("slug") String slug);

    @Query("""
        SELECT m FROM ModelEntity m
        JOIN FETCH m.provider p
        WHERE m.modelId = :modelId
        ORDER BY p.displayOrder ASC, m.displayOrder ASC, m.id ASC
    """)
    List<ModelEntity> findAllByModelId(@Param("modelId") String modelId);

    @Query(value = """
        SELECT m.* FROM models m
        WHERE m.provider_id = :providerId
          AND m.is_enabled = TRUE
          AND m.aliases @> CAST(:aliasJson AS jsonb)
        ORDER BY m.display_order ASC, m.id ASC
        """, nativeQuery = true)
    List<ModelEntity> findByProviderIdAndAlias(
        @Param("providerId") String providerId,
        @Param("aliasJson") String aliasJson
    );

    @Query(value = """
        SELECT m.* FROM models m
        JOIN model_providers p ON m.provider_id = p.id
        WHERE m.is_enabled = TRUE
          AND p.is_enabled = TRUE
          AND m.aliases @> CAST(:aliasJson AS jsonb)
        ORDER BY p.display_order ASC, m.display_order ASC, m.id ASC
        """, nativeQuery = true)
    List<ModelEntity> findByGlobalAlias(
        @Param("aliasJson") String aliasJson
    );

    @Query(value = """
        SELECT m.* FROM models m
        WHERE m.aliases @> CAST(:aliasJson AS jsonb)
        ORDER BY m.display_order ASC, m.id ASC
        """, nativeQuery = true)
    List<ModelEntity> findAllByAlias(
        @Param("aliasJson") String aliasJson
    );

    default List<ModelEntity> findByProviderIdAndAliasValue(String providerId, String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        String aliasJson = "[\"" + alias.replace("\"", "\\\"") + "\"]";
        return findByProviderIdAndAlias(providerId, aliasJson);
    }

    default List<ModelEntity> findByGlobalAliasValue(String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        String aliasJson = "[\"" + alias.replace("\"", "\\\"") + "\"]";
        return findByGlobalAlias(aliasJson);
    }

    default List<ModelEntity> findAllByAliasValue(String alias) {
        if (alias == null || alias.isBlank()) {
            return List.of();
        }
        String aliasJson = "[\"" + alias.replace("\"", "\\\"") + "\"]";
        return findAllByAlias(aliasJson);
    }
}
