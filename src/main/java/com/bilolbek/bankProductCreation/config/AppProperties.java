package com.bilolbek.bankProductCreation.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix="app")
public record AppProperties(@NotNull Path dataDir,@NotNull URI publicBaseUrl) {
    public AppProperties {
        if(publicBaseUrl!=null && (!Set.of("http","https").contains(publicBaseUrl.getScheme()) || publicBaseUrl.getHost()==null))
            throw new IllegalArgumentException("app.public-base-url must be an absolute HTTP(S) URL");
    }
}
