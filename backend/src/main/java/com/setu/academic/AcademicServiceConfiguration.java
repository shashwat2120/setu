package com.setu.academic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.academic.application.IdentityEventProjector;
import com.setu.academic.readmodel.ReadModelPersonDirectory;
import com.setu.academic.readmodel.ReadModelRoleGrantSource;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.authorization.GuardianDelegationChecker;
import com.setu.shared.authorization.RoleBasedTenantContext;
import com.setu.shared.authorization.RoleBasedTenantMembership;
import com.setu.shared.authorization.RoleGrantSource;
import com.setu.shared.identity.PersonDirectory;
import com.setu.shared.identity.TenantMembership;
import com.setu.shared.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the Academic service provides for itself that the monolith gets from Identity: the authorization context, tenant lookups and
 * the people directory, all answered from the local read model of Identity's data ({@code institution.rm_*}), so no request leaves the
 * process to be authorized. The decision logic is the shared {@link AuthorizationEngine}, identical to Identity's; only where the roles
 * come from differs. Active only when {@code setu.service=academic}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "setu.service", havingValue = "academic")
public class AcademicServiceConfiguration {

    @Bean
    RoleGrantSource roleGrantSource(JdbcClient jdbc) {
        return new ReadModelRoleGrantSource(jdbc);
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

    @Bean
    PersonDirectory personDirectory(JdbcClient jdbc) {
        return new ReadModelPersonDirectory(jdbc);
    }

    @Bean
    IdentityEventProjector identityEventProjector(JdbcClient jdbc, ObjectMapper objectMapper) {
        return new IdentityEventProjector(jdbc, objectMapper);
    }
}
