package com.setu.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for the Academic service (Sprint 6A.3): institution, catalog, enrollment, directory and guardian access, started on their
 * own with the shared kernel. Everything those modules used to get from Identity in-process comes from {@link AcademicServiceConfiguration}
 * instead: roles, tenant and people from a local read model fed by Identity's events; tokens verified against Identity's published keys
 * ({@code setu.auth.mode=remote}); logins revoked through the shared revocation list.
 * <p>
 * It scans exactly those packages plus {@code com.setu.shared} minus {@code shared.admin} and {@code shared.seed}, which span services
 * (onboarding creates an account, a person and a profile together; the seeder calls everything). Database access is unchanged until 6B.2
 * gives each service its own database; Flyway stays with the monolith's migration run.
 * <p>
 * Inert inside the monolith: it activates only when {@code setu.service=academic}, which {@link #main} sets. A plain
 * {@code @Configuration}, not {@code @SpringBootConfiguration}, so tests looking for the application class keep finding BackendApplication.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class, excludeName = {
    // Spring Modulith's runtime/observability describe the monolith's module graph and look for its @SpringBootApplication.
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration"
})
@AutoConfigurationPackage(basePackages = {"com.setu.academic", "com.setu.institution", "com.setu.catalog", "com.setu.enrollment",
    "com.setu.directory", "com.setu.guardianaccess", "com.setu.shared"})
@ComponentScan(basePackages = {"com.setu.academic", "com.setu.institution", "com.setu.catalog", "com.setu.enrollment",
    "com.setu.directory", "com.setu.guardianaccess", "com.setu.shared"}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.setu\\.shared\\.(admin|seed)\\..*")
})
@EnableScheduling
@ConditionalOnProperty(name = "setu.service", havingValue = "academic")
public class AcademicServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AcademicServiceApplication.class);
        Map<String, Object> defaults = new HashMap<>(Map.of(
            "setu.service", "academic",
            "app.discovery.service", "academic",
            "setu.auth.mode", "remote",
            "app.readmodel.consumer.enabled", "true",
            "app.readmodel.consumer.group-id", "setu-academic",
            "app.readmodel.consumer.aggregates", "user_roles,persons",
            "APPLICATION_NAME", "academic-service",
            "spring.flyway.enabled", "false",
            "SERVER_PORT", "8082"));
        // no live connections here: leave the shared WebSocket/STOMP support to the services that serve them
        defaults.put("app.realtime.enabled", "false");
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
