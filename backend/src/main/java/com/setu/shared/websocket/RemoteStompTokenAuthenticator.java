package com.setu.shared.websocket;

import com.setu.shared.security.RemoteTokenAuthenticator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** {@link StompTokenAuthenticator} for every service except Identity: the same verification an HTTP request gets (published keys, then the revocation list). */
@Component
@ConditionalOnProperty(name = "setu.auth.mode", havingValue = "remote")
public class RemoteStompTokenAuthenticator implements StompTokenAuthenticator {

    private final RemoteTokenAuthenticator authenticator;

    public RemoteStompTokenAuthenticator(RemoteTokenAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Optional<UUID> resolveUserId(String bearerToken) {
        return authenticator.authenticate(bearerToken).map(claims -> claims.userId());
    }
}
