package io.modivra.model.discovery;

import io.modivra.model.dto.DiscoveredModelDto;
import io.modivra.model.entity.ApiMode;
import io.modivra.model.entity.ModelProviderEntity;
import java.util.List;

public interface ModelDiscoveryAdapter {

    /**
     * Determines whether this adapter supports the given API mode and provider identifier.
     */
    boolean supports(ApiMode apiMode, String providerId);

    /**
     * Discovers models available from the provider.
     * Executes synchronously on virtual threads. Returns empty list on error.
     */
    List<DiscoveredModelDto> discoverModels(ModelProviderEntity provider);

    /**
     * Precedence order. Specific adapters have lower order (10-30); generic adapters order 100.
     */
    default int getOrder() {
        return 100;
    }
}
