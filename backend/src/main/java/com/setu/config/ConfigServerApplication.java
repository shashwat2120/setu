package com.setu.config;

import com.setu.shared.JacksonConfig;
import com.setu.shared.web.CorrelationIdFilterConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

/**
 * Entry point for the config server (Sprint 6A.4): serves each service its configuration, and its secrets, over HTTP, so that no secret is
 * in the repository, in an image, or on a developer's disk beyond the one directory the server reads. Services opt in with
 * {@code spring.config.import=optional:setu-config:http://host:8888} and their own token (see {@code com.setu.shared.config}); without it
 * they read the environment and {@code .env.local} exactly as before.
 * <p>
 * No database, no messaging, no Spring Security (each request is authenticated against its application's token by {@link ConfigController}).
 * Not Spring Cloud Config Server, for the same reason the gateway is not Spring Cloud Gateway: no release train supports this project's Boot
 * (docs/contracts/gateway.md section 1). Inert inside the monolith: active only when {@code setu.service=config}, which {@link #main} sets.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = {
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
    "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
    "org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration",
    "org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
    "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
    "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration",
    "org.springframework.modulith.events.jackson.JacksonEventSerializationConfiguration",
    "org.springframework.modulith.events.jackson2.Jackson2EventSerializationConfiguration",
    "org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration",
    "org.springframework.modulith.events.jpa.archiving.ArchivingAutoConfiguration",
    "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
    "org.springframework.modulith.core.config.ApplicationModuleInitializerRuntimeVerification",
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration",
    "org.springframework.modulith.observability.autoconfigure.SpringDataRestModuleObservabilityAutoConfiguration"
})
@ComponentScan(basePackages = "com.setu.config")
@Import({JacksonConfig.class, CorrelationIdFilterConfig.class})
@ConditionalOnProperty(name = "setu.service", havingValue = "config")
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ConfigServerApplication.class);
        application.setDefaultProperties(Map.of(
            "setu.service", "config",
            // not the advisor: no model client to create
            "AI_PROVIDER", "none",
            "APPLICATION_NAME", "config-server",
            "spring.flyway.enabled", "false",
            "SERVER_PORT", "8888"));
        application.run(args);
    }
}
