package com.setu.identity.infrastructure.web;

import com.setu.identity.domain.Role;
import com.setu.identity.domain.UserRole;
import com.setu.identity.infrastructure.RoleRepository;
import com.setu.identity.infrastructure.UserRoleRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.identity.PersonDirectory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 16 §frontend-foundation: the one small, genuinely necessary gap Phase 3's own audit found — no endpoint
 * anywhere let an authenticated caller discover their OWN identity (who they are and which roles they hold, in which tenants).
 * <p>
 * Sprint 6 coupling audit (docs/extraction-audit.md): this used to ALSO resolve the caller's student/faculty/guardian profile ids, which
 * made identity depend on the directory module (and so, once extracted, the Identity service on the Academic service, while Academic
 * already depends on Identity: a cycle). The profile ids now come from {@code GET /directory/me/profiles}, owned by directory; the
 * frontend asks both. This endpoint is account and roles only. Every prior day's work
 * assumed the caller already knew their own ids (test fixtures construct them directly); a real browser
 * session logging in via a bare email/password has no way to learn any of that otherwise, so role-aware
 * navigation and self-scoped data fetching (a student's own risk score, a faculty member's own sections) would
 * be structurally impossible to build without either this endpoint or the frontend inventing/guessing identity
 * data — exactly what it must never do. This is read-only, self-only (never accepts a foreign user id — always
 * resolved from the authenticated principal, same as every other self-scoped port in this codebase), and adds
 * no new business logic: it only aggregates lookups that already exist (`UserRoleRepository`, {@link PersonDirectory}).
 */
@RestController
@RequestMapping("/identity")
public class CurrentUserController {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final PersonDirectory personDirectory;
    private final AuthorizationContext authorizationContext;

    public CurrentUserController(UserRoleRepository userRoleRepository, RoleRepository roleRepository,
                                  PersonDirectory personDirectory, AuthorizationContext authorizationContext) {
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.personDirectory = personDirectory;
        this.authorizationContext = authorizationContext;
    }

    public record RoleGrant(String roleCode, UUID tenantId) {
    }

    public record MeResponse(UUID userId, String email, UUID personId, String fullName, List<RoleGrant> roles) {
    }

    @GetMapping("/me")
    public MeResponse me() {
        UUID userId = authorizationContext.currentUserId();
        Instant now = Instant.now();

        Map<UUID, Role> rolesById = roleRepository.findAllById(
            userRoleRepository.findByUserId(userId).stream().map(UserRole::roleId).toList())
            .stream().collect(java.util.stream.Collectors.toMap(Role::id, r -> r));

        List<UserRole> active = userRoleRepository.findByUserId(userId).stream()
            .filter(ur -> ur.isActive(now)).toList();

        List<RoleGrant> grants = active.stream()
            .map(ur -> new RoleGrant(rolesById.get(ur.roleId()).code().name(), ur.tenantId()))
            .toList();

        Optional<PersonDirectory.PersonSummary> person = personDirectory.findByUserId(userId);
        UUID personId = person.map(PersonDirectory.PersonSummary::id).orElse(null);
        String fullName = person.map(PersonDirectory.PersonSummary::legalName).orElse(null);
        String email = person.map(PersonDirectory.PersonSummary::email).orElse(null);

        return new MeResponse(userId, email, personId, fullName, grants);
    }
}
