package io.modivra.model.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum ApiMode {
    OPENAI_COMPATIBLE("openai_compatible"),
    OPENAI_RESPONSES("openai_responses"),
    ANTHROPIC_MESSAGES("anthropic_messages");

    private final String value;

    ApiMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ApiMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        for (ApiMode mode : values()) {
            if (mode.value.equalsIgnoreCase(value) || mode.name().equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("Unknown ApiMode: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
