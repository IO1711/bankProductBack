package com.bilolbek.bankProductCreation.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="app.document")
public record DocumentPipelineProperties(@NotNull Mode mode) {
    public enum Mode { DEMO, REAL }
}
