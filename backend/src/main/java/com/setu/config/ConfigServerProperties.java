package com.setu.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Map;

/**
 * @param configDir  non-secret configuration, in the repository ({@code infra/config})
 * @param secretsDir secrets, <b>outside</b> the repository (git-ignored by default); read with the same file layout and layered on top
 * @param tokens     application name -> the bearer token that application must present. One token per application, so a leaked Academic
 *                   token cannot read Identity's signing key. The server refuses to start without at least one, and without long ones.
 */
@ConfigurationProperties("app.configserver")
public record ConfigServerProperties(
    @DefaultValue("../infra/config") Path configDir,
    @DefaultValue("../infra/config/secrets") Path secretsDir,
    @DefaultValue Map<String, String> tokens) {

    public static final int MIN_TOKEN_LENGTH = 24;
}
