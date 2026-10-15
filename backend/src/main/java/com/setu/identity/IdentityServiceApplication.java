package com.setu.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Map;

/**
 * Entry point for the Identity service (Sprint 6A.2): the same code as the {@code identity} module, started on its own with nothing but
 * the shared kernel beside it. It owns accounts, passkeys, passwords, sessions, roles and the authorization decision, issues the ES256
 * access tokens, and publishes their keys at {@code /.well-known/jwks.json}.
 * <p>
 * It scans {@code com.setu.identity} and {@code com.setu.shared} only, minus the shared parts that exist to span modules
 * ({@code shared.admin}: onboarding that creates a profile and an account together; {@code shared.seed}: demo data; {@code shared.ai}:
 * policy-search embeddings). Guardian delegation needs the guardian-access module, which is not here, so the authorization service
 * treats delegation as unavailable and denies it. Database access is unchanged until 6B.2 splits the schemas into per-service
 * databases; Flyway stays with the monolith's migration run, so this service does not migrate.
 * <p>
 * Inert inside the monolith: it only activates when {@code setu.service=identity}, which {@link #main} sets.
 */
// A plain @Configuration, not @SpringBootConfiguration: tests in com.setu.identity.* look upwards for the latter and must keep finding BackendApplication.
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class, excludeName = {
    // Spring Modulith's runtime/observability describe the monolith's module graph and look for its @SpringBootApplication; a single-module service has neither.
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration"
})
@AutoConfigurationPackage(basePackages = {"com.setu.identity", "com.setu.shared"})
@ComponentScan(basePackages = {"com.setu.identity", "com.setu.shared"}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.setu\\.shared\\.(admin|seed|ai)\\..*")
})
@EnableScheduling
@ConditionalOnProperty(name = "setu.service", havingValue = "identity")
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(IdentityServiceApplication.class);
        application.setDefaultProperties(Map.of(
            "setu.service", "identity",
            "app.discovery.service", "identity",
            "spring.application.name", "identity-service",
            "spring.flyway.enabled", "false",
            "server.port", "8081"));
        application.run(args);
    }
}
