package com.setu.identity.infrastructure.authorization;

import com.setu.shared.authorization.AuthorizationEngine;
import com.setu.shared.authorization.GuardianDelegationChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Identity's authorization context: the shared {@link AuthorizationEngine} reading roles from Identity's own tables. The decision logic
 * (and its documentation) lives in the engine so that every extracted service makes exactly the same decision from its own copy of the
 * role data; this class only chooses where the roles come from.
 */
@Component
@EnableConfigurationProperties(AuthorizationProperties.class)
public class AuthorizationService extends AuthorizationEngine {

    public AuthorizationService(IdentityRoleGrantSource roles, ObjectProvider<GuardianDelegationChecker> guardianDelegationChecker,
                                 AuthorizationProperties properties) {
        super(roles, guardianDelegationChecker, properties.getStepUpValidityMinutes());
    }
}
