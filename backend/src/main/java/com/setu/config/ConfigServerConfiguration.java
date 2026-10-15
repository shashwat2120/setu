package com.setu.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;

/**
 * Wires the config server and refuses to start in a state that would be unsafe: no tokens at all (anyone could read every secret), a token
 * too short to resist guessing, or a configuration directory that does not exist (a server that serves nothing would let every
 * application start on its defaults without anyone noticing). The messages never contain a token. Active only when {@code setu.service=config}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "setu.service", havingValue = "config")
@EnableConfigurationProperties(ConfigServerProperties.class)
public class ConfigServerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConfigServerConfiguration.class);

    @Bean
    ConfigAssembler configAssembler(ConfigServerProperties properties) {
        if (properties.tokens().isEmpty()) {
            throw new IllegalStateException("app.configserver.tokens is empty: set APP_CONFIGSERVER_TOKENS_<APPLICATION> for each "
                + "application that may fetch its configuration (for example APP_CONFIGSERVER_TOKENS_IDENTITY). Refusing to serve secrets to anyone.");
        }
        properties.tokens().forEach((application, token) -> {
            if (!ConfigAssembler.NAME.matcher(application).matches()) {
                throw new IllegalStateException("app.configserver.tokens has an application name that is not lowercase letters, digits and hyphens");
            }
            if (token == null || token.strip().length() < ConfigServerProperties.MIN_TOKEN_LENGTH) {
                throw new IllegalStateException("the config token for '" + application + "' is shorter than "
                    + ConfigServerProperties.MIN_TOKEN_LENGTH + " characters: generate a long random one");
            }
        });
        if (!Files.isDirectory(properties.configDir())) {
            throw new IllegalStateException("app.configserver.configdir does not exist: " + properties.configDir().toAbsolutePath());
        }
        if (!Files.isDirectory(properties.secretsDir())) {
            log.warn("the secrets directory {} does not exist: only non-secret configuration will be served", properties.secretsDir().toAbsolutePath());
        }
        log.info("config server: serving {} from {} (secrets from {}) to {} application(s)", "configuration", properties.configDir().toAbsolutePath(),
            properties.secretsDir().toAbsolutePath(), properties.tokens().size());
        return new ConfigAssembler(properties.configDir(), properties.secretsDir());
    }
}
