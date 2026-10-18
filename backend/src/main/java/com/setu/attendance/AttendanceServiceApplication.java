package com.setu.attendance;

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
 * Entry point for the Attendance service (Sprint 6A.5): the {@code attendance} module (sessions, rotating QR codes, scans, records,
 * staff attendance) started on its own with the shared kernel, including the shared WebSocket/STOMP support that serves the live board.
 * Everything it used to ask Identity and Academic in-process it now answers from a local copy ({@code attendance.rm_*}, migration V81) that Identity's and Academic's events keep current; tokens are verified
 * against Identity's published keys and the revocation list. See {@code docs/contracts/extracted-services.md}.
 * <p>
 * Scans {@code com.setu.attendance} and {@code com.setu.shared}, minus the parts of the kernel that span services ({@code shared.admin},
 * {@code shared.seed}, {@code shared.ai}). Database access is unchanged until 6B.2 gives each service its own database; Flyway stays with the
 * monolith's migration run.
 * <p>
 * Inert inside the monolith: active only when {@code setu.service=attendance}, which {@link #main} sets. A plain {@code @Configuration}, not
 * {@code @SpringBootConfiguration}, so tests looking for the application class keep finding BackendApplication.
 */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = UserDetailsServiceAutoConfiguration.class, excludeName = {
    "org.springframework.modulith.runtime.autoconfigure.SpringModulithRuntimeAutoConfiguration",
    // its endpoint needs the runtime bean excluded above: left in, the service dies at start-up (found by running the services in containers)
    "org.springframework.modulith.actuator.autoconfigure.ApplicationModulesEndpointConfiguration",
    "org.springframework.modulith.observability.autoconfigure.ModuleObservabilityAutoConfiguration"
})
@AutoConfigurationPackage(basePackages = {"com.setu.attendance", "com.setu.shared"})
@ComponentScan(basePackages = {"com.setu.attendance", "com.setu.shared"}, excludeFilters = {
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
    @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.setu\\.shared\\.(admin|seed|ai)\\..*")
})
@EnableScheduling
@ConditionalOnProperty(name = "setu.service", havingValue = "attendance")
public class AttendanceServiceApplication {

    /** The events a service on top of Academic follows to keep its read model: Identity's role changes and Academic's facts. */
    public static final String FOLLOWED_AGGREGATES = "user_roles,student_profiles,faculty_profiles,faculty_assignments,enrollments,colleges,"
        + "meeting_patterns,course_sections,guardian_access_grants";

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AttendanceServiceApplication.class);
        Map<String, Object> defaults = new HashMap<>(Map.of(
            "setu.service", "attendance",
            "setu.auth.mode", "remote",
            "app.readmodel.schema", "attendance",
            "app.readmodel.consumer.enabled", "true",
            "app.readmodel.consumer.group-id", "setu-attendance",
            "app.readmodel.consumer.aggregates", FOLLOWED_AGGREGATES,
            "app.discovery.service", "attendance",
            "APPLICATION_NAME", "attendance-service",
            "spring.flyway.enabled", "false",
            "SERVER_PORT", "8086"));
        // the live board is served here, so the shared WebSocket/STOMP support stays on (app.realtime.enabled defaults to true)
        // not the advisor: no model client to create, and no warning about a key it will never use
        defaults.put("AI_PROVIDER", "none");
        application.setDefaultProperties(defaults);
        application.run(args);
    }
}
