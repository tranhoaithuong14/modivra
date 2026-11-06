package io.modivra.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record ProvidersResponseDto(
    @JsonProperty("providers") List<ProviderDto> providers
) {
    public ProvidersResponseDto {
        providers = providers != null ? List.copyOf(providers) : List.of();
    }
}
