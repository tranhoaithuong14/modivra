package io.modivra.model.routing;

import io.modivra.model.entity.ModelEntity;
import io.modivra.model.entity.ModelProviderEntity;

public record ResolvedChatModel(
    String canonicalId,
    String upstreamModelId,
    ModelProviderEntity providerEntity,
    ModelEntity modelEntity
) {
    public String providerId() {
        return providerEntity != null ? providerEntity.getId() : null;
    }

    public String baseUrl() {
        return providerEntity != null ? providerEntity.getBaseUrl() : null;
    }

    public String apiKeyEnv() {
        return providerEntity != null ? providerEntity.getApiKeyEnv() : null;
    }
}
