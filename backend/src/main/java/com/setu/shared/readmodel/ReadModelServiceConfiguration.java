package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.academic.AcademicDirectory;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.authorization.RoleBasedTenantContext;
import com.setu.shared.authorization.RoleBasedTenantMembership;
import com.setu.shared.authorization.RoleGrantSource;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.identity.TenantMembership;
import com.setu.shared.resilience.GuardedCall;
import com.setu.shared.tenant.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;

/**
 * Everything a service that sits on top of Academic (learning, assessment, attendance) needs in place of what it used to get in-process from
 * Identity and Academic: authorization from a local copy of the roles, the people, enrollments, assignments, sections, guardian grants and
 * college time zones, kept current by the projectors that the shared consumer feeds. Nothing here leaves the process at request time.
 * <p>
 * The decision logic is the same {@link AuthorizationEngine} Identity and Academic use; only where the data comes from differs. Active only
 * when {@code app.readmodel.schema} names the service's schema, which each extracted service's entry point sets.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.readmodel.schema")
public class ReadModelServiceConfiguration {

    @Bean
    RoleGrantSource roleGrantSource(JdbcClient jdbc, @Value("${app.readmodel.schema}") String schema) {
        return new ReadModelRoleGrantSource(jdbc, schema);
    }

    @Bean
    AuthorizationContext authorizationContext(RoleGrantSource roles, ObjectProvider<GuardianDelegationChecker> delegation,
                                              @Value("${app.security.authorization.step-up-validity-minutes:5}") long stepUpValidityMinutes) {
        return new AuthorizationEngine(roles, delegation, stepUpValidityMinutes);
    }

    @Bean
    TenantContext tenantContext(RoleGrantSource roles, AuthorizationContext authorizationContext) {
        return new RoleBasedTenantContext(roles, authorizationContext);
    }

    @Bean
    TenantMembership tenantMembership(RoleGrantSource roles) {
        return new RoleBasedTenantMembership(roles);
    }

    /**
     * The local copy of the grants, with every "yes" confirmed by Academic when {@code setu.guardian-verify.url} is set (the extracted
     * deployments set it). Unset, as in a single-process run or a test of the local logic alone, the local copy is trusted as before.
     */
    @Bean
    GuardianDelegationChecker guardianDelegationChecker(JdbcClient jdbc, @Value("${app.readmodel.schema}") String schema,
                                                        ObjectProvider<GuardianGrantVerifier> owner, MeterRegistry meters) {
        GuardianDelegationChecker local = new ReadModelGuardianDelegationChecker(jdbc, schema);
        GuardianGrantVerifier verifier = owner.getIfAvailable();
        return verifier == null ? local : new VerifiedGuardianDelegationChecker(local, verifier, meters);
    }

    /** Counts, on a schedule, the ids this service holds that its copy of their owner's data does not know (what the dropped foreign keys used to forbid). */
    @Bean
    ReferenceReconciler referenceReconciler(JdbcClient jdbc, @Value("${app.readmodel.schema}") String schema, MeterRegistry meters) {
        return new ReferenceReconciler(jdbc, schema, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "setu.guardian-verify.url")
    GuardianGrantVerifier guardianGrantVerifier(@Value("${setu.guardian-verify.url}") String url,
                                                @Value("${setu.internal.token:}") String token,
                                                @Value("${setu.guardian-verify.connect-timeout-ms:300}") long connectMs,
                                                @Value("${setu.guardian-verify.response-timeout-ms:800}") long responseMs,
                                                @Value("${setu.guardian-verify.max-concurrent:20}") int maxConcurrent,
                                                ObjectMapper mapper, MeterRegistry meters) {
        var settings = new GuardedCall.Settings(50, 10, 20, Duration.ofSeconds(10), maxConcurrent, Duration.ZERO);
        // a transport failure or an unexpected answer is the dependency failing; nothing else is ever thrown by the call
        var guard = new GuardedCall("academic-guardian-grants", settings, e -> true, meters);
        return new RemoteGuardianGrantVerifier(url, token, Duration.ofMillis(connectMs), Duration.ofMillis(responseMs), mapper, guard);
    }

    @Bean
    AcademicDirectory academicDirectory(JdbcClient jdbc, @Value("${app.readmodel.schema}") String schema) {
        return new ReadModelAcademicDirectory(jdbc, schema);
    }

    @Bean
    CourseSectionDirectory courseSectionDirectory(JdbcClient jdbc, @Value("${app.readmodel.schema}") String schema) {
        return new ReadModelCourseSectionDirectory(jdbc, schema);
    }

    @Bean
    ReadModelProjector identityProjector(JdbcClient jdbc, ObjectMapper mapper, @Value("${app.readmodel.schema}") String schema) {
        return new IdentityEventProjector(jdbc, mapper, schema, false);
    }

    @Bean
    ReadModelProjector academicProjector(JdbcClient jdbc, ObjectMapper mapper, @Value("${app.readmodel.schema}") String schema) {
        return new AcademicEventProjector(jdbc, mapper, schema);
    }
}
