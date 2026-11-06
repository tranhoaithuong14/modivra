package io.modivra.model.controller;

import io.modivra.model.dto.ModelsResponseDto;
import io.modivra.model.dto.ProvidersResponseDto;
import io.modivra.model.dto.SyncResultDto;
import io.modivra.model.service.ModelRegistryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ModelController {

    private final ModelRegistryService modelRegistryService;

    public ModelController(ModelRegistryService modelRegistryService) {
        this.modelRegistryService = modelRegistryService;
    }

    @GetMapping(value = "/models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ModelsResponseDto getModels() {
        return modelRegistryService.getModelsResponse();
    }

    @GetMapping(value = "/providers", produces = MediaType.APPLICATION_JSON_VALUE)
    public ProvidersResponseDto getProviders() {
        return modelRegistryService.getProviders();
    }

    @PostMapping(value = "/models/sync", produces = MediaType.APPLICATION_JSON_VALUE)
    public SyncResultDto syncModels(
        @RequestParam(name = "provider", required = false) String providerId
    ) {
        return modelRegistryService.syncModels(providerId);
    }
}
