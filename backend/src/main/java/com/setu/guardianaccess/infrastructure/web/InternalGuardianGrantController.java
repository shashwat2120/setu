package com.setu.guardianaccess.infrastructure.web;

import com.setu.shared.authorization.GuardianDelegationChecker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * The authoritative answer to "does this guardian hold this permission for this student right now?", for the other services to ask at the
 * moment it matters. Their own copy of the grants is current within seconds; this is the check that makes a revocation take effect at once
 * (docs/contracts/data-ownership.md). It answers from the grants themselves, in this service's own transaction, and nothing else.
 * <p>
 * Internal only: {@code /internal/**} requires the service-to-service token ({@code InternalCallAuthFilter}) and is not routed by the gateway.
 */
@RestController
@RequestMapping("/internal/guardian-grants")
public class InternalGuardianGrantController {

    public record Verdict(boolean active) {
    }

    private final GuardianDelegationChecker checker;

    public InternalGuardianGrantController(GuardianDelegationChecker checker) {
        this.checker = checker;
    }

    @GetMapping("/verify")
    public Verdict verify(@RequestParam UUID guardianUserId, @RequestParam UUID studentProfileId, @RequestParam UUID tenantId,
                          @RequestParam String permission) {
        return new Verdict(checker.hasActiveDelegatedAccess(guardianUserId, studentProfileId, tenantId, permission, Instant.now()));
    }
}
