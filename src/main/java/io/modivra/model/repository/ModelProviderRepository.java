package io.modivra.model.repository;

import io.modivra.model.entity.ModelProviderEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ModelProviderRepository extends JpaRepository<ModelProviderEntity, String> {

    List<ModelProviderEntity> findAllByIsEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<ModelProviderEntity> findAllByIsEnabledTrueOrderByDisplayOrderAsc();

    List<ModelProviderEntity> findByIsEnabledTrueAndSupportsDiscoveryTrueOrderByDisplayOrderAsc();

    List<ModelProviderEntity> findByIsEnabledTrueAndSupportsDiscoveryTrueOrderByDisplayOrderAscIdAsc();

    List<ModelProviderEntity> findAllByOrderByDisplayOrderAscIdAsc();

    List<ModelProviderEntity> findAllByOrderByDisplayOrderAsc();

    Optional<ModelProviderEntity> findByIdAndIsEnabledTrue(String id);
}
