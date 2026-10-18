package com.setu.aiexplain;

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

import java.util.HashMap;
import java.util.Map;

/**
 * Entry point for the AI service (Sprint 6B.3): the {@code aiexplain} module, the Spring AI chat orchestration (providers, the advisor's tools,
 * conversation memory, rate limiting, asynchronous jobs), started on its own with the shared kernel. See {@code docs/contracts/ai-service.md}.
 * <p>
 * <b>It has no datasource.</b> No database driver is wired, no JPA, no Flyway; the persistence auto-configurations are excluded outright, so a
 * stray repository could not even start. Everything it learns about a person it asks for through {@link com.setu.shared.aifacts.AiFacts},
 * which here is {@code RemoteAiFacts}: calls to {@code /ai-facts/**} on the service that holds the data, carrying the <i>user's own</i> bearer
 * token, so the owner authorizes every fact for the real principal. Its own state (a conversation's last few exchanges, job records, rate-limit
 * counters) is in memory or in Redis, with an expiry.
 * <p>
 * Scans {@code com.setu.aiexplain} and {@code com.setu.shared}, minus the parts of the kernel that need a database or span services
 * ({@code shared.admin}, {@code shared.seed}, {@code shared.ai} (the embedders, used where policy search runs), {@code shared.governance},
 * {@code shared.readmodel}). Inert inside the monolith: active only when {@code setu.service=ai}, which {@link #main} sets.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class, excludeName = {
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    // its endpoint needs the runtime bean excluded above: left in, the service dies at start-up (found by running the services in containers)
    "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.metrics.DataSourcePoolMetricsAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.metrics.HibernateMetricsAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayEndpointAutoConfiguration",
    "org.springframework.modulith.events.jpa.JpaEventPublicationAutoConfiguration",
    "org.springframework.modulith.events.jpa.archiving.ArchivingAutoConfiguration",
    "org.springframework.modulith.events.config.EventPublicationAutoConfiguration",
    "org.springframework.modulith.events.config.EventExternalizationAutoConfiguration"
})
@AutoConfigurationPackage(basePackages = {"com.setu.aiexplain", "com.setu.shared"})
@ComponentScan(basePackages = {"com.setu.aiexplain", "com.setu.shared"}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.setu\\.shared\\.(admin|seed|ai|governance|readmodel)\\..*")
})
@ConditionalOnProperty(name = "setu.service", havingValue = "ai")
public class AiServiceApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiServiceApplication.class);
        Map<String, Object> defaults = new HashMap<>(Map.of(
            "setu.service", "ai",
            "setu.auth.mode", "remote",
            "setu.ai-facts.url", "${GATEWAY_URL:http://localhost:8080}",
            "app.discovery.service", "ai",
            "APPLICATION_NAME", "ai-service",
            "spring.flyway.enabled", "false",
            "app.readmodel.consumer.enabled", "false",
            "app.notification.consumer.enabled", "false",
            // no database here, so the pool-sizing rule does not apply: a chat holds its thread for the length of a model call
            "SERVER_MAX_THREADS", "200",
            "SERVER_PORT", "8088"));
        // job status is pushed over WebSocket/STOMP from here, so the shared support stays on (app.realtime.enabled defaults to true)
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
