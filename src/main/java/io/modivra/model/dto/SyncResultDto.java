package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record SyncResultDto(
    @JsonProperty("status") String status,
    @JsonProperty("providers_synced") int providersSynced,
    @JsonProperty("models_upserted") int modelsUpserted,
    @JsonProperty("message") String message
) {
    public static SyncResultDto success(int providersSynced, int modelsUpserted) {
        return new SyncResultDto("completed", providersSynced, modelsUpserted, "Model synchronization completed successfully");
    }

    public static SyncResultDto success(List<String> providerIds, int modelsUpserted) {
        return new SyncResultDto("completed", providerIds != null ? providerIds.size() : 0, modelsUpserted, "Model synchronization completed successfully");
    }

    public static SyncResultDto partial(int providersSynced, int modelsUpserted, String message) {
        return new SyncResultDto("partial", providersSynced, modelsUpserted, message);
    }
}
