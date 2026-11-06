package io.modivra.model.entity;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class ApiModeConverter implements AttributeConverter<ApiMode, String> {

    @Override
    public String convertToDatabaseColumn(ApiMode attribute) {
        return attribute != null ? attribute.getValue() : ApiMode.OPENAI_COMPATIBLE.getValue();
    }

    @Override
    public ApiMode convertToEntityAttribute(String dbData) {
        return dbData != null ? ApiMode.fromValue(dbData) : ApiMode.OPENAI_COMPATIBLE;
    }
}
