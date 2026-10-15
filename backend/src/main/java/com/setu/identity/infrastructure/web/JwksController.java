package com.setu.identity.infrastructure.web;

import com.setu.identity.infrastructure.security.JwtKeyStore;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Publishes the public keys that verify Setu access tokens (RFC 7517 JWK Set). Anyone may read it: it holds public parameters only, and
 * it is how the gateway and every other service verify a token without sharing a secret with Identity. Cacheable for a short time so a
 * key rotation reaches verifiers within minutes.
 */
@RestController
public class JwksController {

    private final JwtKeyStore keyStore;

    public JwksController(JwtKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = "application/jwk-set+json")
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .contentType(MediaType.parseMediaType("application/jwk-set+json"))
            .body(keyStore.jwksJson());
    }
}
