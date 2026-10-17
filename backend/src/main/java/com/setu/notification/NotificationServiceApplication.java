package com.setu.notification;

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
 * Entry point for the Notification/Realtime service (Sprint 6B.3): the {@code notification} module (in-app notifications built from business
 * events, and their live delivery over WebSocket/STOMP) started on its own with the shared kernel.
 * <p>
 * It consumes two kinds of topic. The business topics (grades, payments, placements, recommendations, interventions, admissions, enrollments,
 * waitlists) are turned into notifications, exactly once per event id. Identity's and Academic's topics feed a local copy
 * ({@code notification.rm_*}, migration V85) that answers the only two questions it has to ask: which account owns this student, and whose
 * enrollment is this. Tokens are verified against Identity's published keys and the revocation list. See
 * {@code docs/contracts/notification-service.md}.
 * <p>
 * Scans {@code com.setu.notification} and {@code com.setu.shared}, minus the parts of the kernel that span services ({@code shared.admin},
 * {@code shared.seed}, {@code shared.ai}). Flyway stays with the migration run of the monolith's database until a service is split off it.
 * <p>
 * Inert inside the monolith: active only when {@code setu.service=notification}, which {@link #main} sets. A plain {@code @Configuration}, not
 * {@code @SpringBootConfiguration}, so tests looking for the application class keep finding BackendApplication.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class, excludeName = {
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration"
})
@AutoConfigurationPackage(basePackages = {"com.setu.notification", "com.setu.shared"})
@ComponentScan(basePackages = {"com.setu.notification", "com.setu.shared"}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.setu\\.shared\\.(admin|seed|ai)\\..*")
})
@EnableScheduling
@ConditionalOnProperty(name = "setu.service", havingValue = "notification")
public class NotificationServiceApplication {

    /** The events a service on top of Academic follows to keep its read model: Identity's role changes and Academic's facts. */
    public static final String FOLLOWED_AGGREGATES = "user_roles,student_profiles,faculty_profiles,faculty_assignments,enrollments,colleges,"
        + "meeting_patterns,course_sections,guardian_access_grants";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(NotificationServiceApplication.class);
        Map<String, Object> defaults = new HashMap<>(Map.of(
            "setu.service", "notification",
            "setu.auth.mode", "remote",
            "app.readmodel.schema", "notification",
            "app.readmodel.consumer.enabled", "true",
            "app.readmodel.consumer.group-id", "setu-notification-readmodel",
            "app.readmodel.consumer.aggregates", FOLLOWED_AGGREGATES,
            "app.discovery.service", "notification",
            "APPLICATION_NAME", "notification-service",
            "spring.flyway.enabled", "false",
            "SERVER_PORT", "8087"));
        // live notifications are pushed from here, so the shared WebSocket/STOMP support stays on (app.realtime.enabled defaults to true)
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
