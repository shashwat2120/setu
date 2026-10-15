package com.setu.gateway;

import com.setu.shared.JacksonConfig;
import com.setu.shared.security.LoginRevocationsConfig;
import com.setu.shared.security.RemoteVerificationConfig;
import com.setu.shared.web.CorrelationIdFilterConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.Map;

/**
 * Entry point for the API gateway (Sprint 6A.4): the one public address of the platform. It has no database, no Spring Security and no
 * business code; it scans only {@code com.setu.gateway} and imports the three kernel pieces it shares with every service: token
 * verification against Identity's published keys, the revocation list, and the correlation id filter.
 * <p>
 * Built on the servlet stack rather than Spring Cloud Gateway: no Spring Cloud release train supports this project's Spring Boot (2025.1.x
 * targets Boot 4.0, 2026.0 needs Boot 4.2), and a gateway that is the platform's single front door should not run on an unsupported
 * combination. The routing table is plain configuration and the pieces are small, so moving to Spring Cloud Gateway once a train matches is
 * a replacement of {@link GatewayFilter}, not of the contract in {@code docs/contracts/gateway.md}.
 * <p>
 * Inert inside the monolith: active only when {@code setu.service=gateway}, which {@link #main} sets. A plain {@code @Configuration}, not
 * {@code @SpringBootConfiguration}, so tests looking for the application class keep finding BackendApplication.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(excludeName = {
    // no database, no migrations, no Kafka, no security chain: the gateway only forwards
    "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
    "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
    "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
    "org.springframework.boot.data.jpa.autoconfigure.JpaRepositoriesAutoConfiguration",
    "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
    "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration",
    "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration",
    // Spring Modulith describes the monolith's module graph and keeps an event publication log in the database; the gateway has neither
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
@ComponentScan(basePackages = "com.setu.gateway")
@Import({JacksonConfig.class, CorrelationIdFilterConfig.class, RemoteVerificationConfig.class, LoginRevocationsConfig.class})
@ConditionalOnProperty(name = "setu.service", havingValue = "gateway")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GatewayApplication.class);
        application.setDefaultProperties(Map.of(
            "setu.service", "gateway",
            "setu.auth.mode", "remote",
            "spring.application.name", "gateway",
            "spring.flyway.enabled", "false",
            "server.port", "8080"));
        application.run(args);
    }
}
