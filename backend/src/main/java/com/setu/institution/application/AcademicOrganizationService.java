package com.setu.institution.application;

import com.setu.institution.domain.AcademicOrganization;
import com.setu.institution.domain.Campus;
import com.setu.institution.infrastructure.AcademicOrganizationRepository;
import com.setu.institution.infrastructure.CampusRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Optional hierarchical academic organization (Day 5 §5). Cycle/self-parent/
 * cross-tenant checks are enforced here rather than as a database CHECK
 * constraint or trigger: validating "no cycle exists across N rows" needs a
 * graph walk, which a declarative CHECK can't express, and a recursive
 * trigger is a heavier, harder-to-test mechanism than one domain-layer
 * method for the amount of write traffic this table sees (institution
 * structure changes rarely, unlike a high-throughput table where a
 * DB-level guarantee would matter more).
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class AcademicOrganizationService {

    private static final String MANAGE_PERMISSION = "MANAGE_INSTITUTION";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";
    private static final int MAX_HIERARCHY_DEPTH = 32; // guards against a pathological/corrupted chain even after cycle-checking

    private final AcademicOrganizationRepository organizationRepository;
    private final CampusRepository campusRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AcademicOrganizationService(AcademicOrganizationRepository organizationRepository,
                                        CampusRepository campusRepository, AuthorizationContext authorizationContext,
                                        AuditLogWriter auditLogWriter) {
        this.organizationRepository = organizationRepository;
        this.campusRepository = campusRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID organizationId) implements CreateResult {
        }

        record CampusNotFound() implements CreateResult {
        }

        record ParentNotFound() implements CreateResult {
        }

        record ParentInDifferentTenant() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID campusId, UUID parentOrganizationId, String name, String type) {
        Optional<Campus> campus = campusRepository.findById(campusId);
        if (campus.isEmpty()) {
            return new CreateResult.CampusNotFound();
        }
        UUID tenantId = campus.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("academic_organization", UUID.randomUUID(), tenantId));

        if (parentOrganizationId != null) {
            Optional<AcademicOrganization> parent = organizationRepository.findById(parentOrganizationId);
            if (parent.isEmpty()) {
                return new CreateResult.ParentNotFound();
            }
            if (!parent.get().tenantId().equals(tenantId)) {
                return new CreateResult.ParentInDifferentTenant();
            }
        }

        UUID actor = authorizationContext.currentUserId();
        AcademicOrganization organization = new AcademicOrganization(
            UUID.randomUUID(), tenantId, campusId, parentOrganizationId, name, type, actor, Instant.now());
        organizationRepository.save(organization);
        auditLogWriter.write(tenantId, actor, "ACADEMIC_ORGANIZATION_CREATED", "institution.academic_organizations",
            organization.id(), Map.of("name", name, "parentOrganizationId", String.valueOf(parentOrganizationId)));
        return new CreateResult.Success(organization.id());
    }

    public sealed interface ReparentResult {
        record Success() implements ReparentResult {
        }

        record NotFound() implements ReparentResult {
        }

        record ParentNotFound() implements ReparentResult {
        }

        record SelfParenting() implements ReparentResult {
        }

        record ParentInDifferentTenant() implements ReparentResult {
        }

        record WouldCreateCycle() implements ReparentResult {
        }
    }

    @Transactional
    public ReparentResult setParent(UUID organizationId, UUID newParentId) {
        Optional<AcademicOrganization> maybeOrg = organizationRepository.findById(organizationId);
        if (maybeOrg.isEmpty()) {
            return new ReparentResult.NotFound();
        }
        AcademicOrganization organization = maybeOrg.get();
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("academic_organization", organization.id(), organization.tenantId()));

        if (newParentId == null) {
            return applyReparent(organization, null);
        }
        if (newParentId.equals(organizationId)) {
            return new ReparentResult.SelfParenting();
        }
        Optional<AcademicOrganization> maybeParent = organizationRepository.findById(newParentId);
        if (maybeParent.isEmpty()) {
            return new ReparentResult.ParentNotFound();
        }
        AcademicOrganization newParent = maybeParent.get();
        if (!newParent.tenantId().equals(organization.tenantId())) {
            return new ReparentResult.ParentInDifferentTenant();
        }
        if (wouldCreateCycle(organizationId, newParentId)) {
            return new ReparentResult.WouldCreateCycle();
        }
        return applyReparent(organization, newParentId);
    }

    /** Walks upward from the PROPOSED parent, following parent_organization_id, checking whether it ever reaches back to `organizationId` — that would mean the new edge closes a cycle. Bounded by MAX_HIERARCHY_DEPTH so a corrupted chain can't spin forever. */
    private boolean wouldCreateCycle(UUID organizationId, UUID proposedParentId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = proposedParentId;
        int steps = 0;
        while (current != null) {
            if (current.equals(organizationId)) {
                return true;
            }
            if (!visited.add(current) || ++steps > MAX_HIERARCHY_DEPTH) {
                return true; // already-corrupted chain — refuse rather than risk making it worse
            }
            current = organizationRepository.findById(current).map(AcademicOrganization::parentOrganizationId).orElse(null);
        }
        return false;
    }

    private ReparentResult applyReparent(AcademicOrganization organization, UUID newParentId) {
        UUID actor = authorizationContext.currentUserId();
        organization.setParent(newParentId, actor, Instant.now());
        organizationRepository.save(organization);
        auditLogWriter.write(organization.tenantId(), actor, "ACADEMIC_ORGANIZATION_REPARENTED",
            "institution.academic_organizations", organization.id(),
            Map.of("newParentOrganizationId", String.valueOf(newParentId)));
        return new ReparentResult.Success();
    }

    @Transactional(readOnly = true)
    public Optional<AcademicOrganization> get(UUID organizationId) {
        Optional<AcademicOrganization> organization = organizationRepository.findById(organizationId);
        organization.ifPresent(o -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("academic_organization", o.id(), o.tenantId())));
        return organization;
    }

    /** Day 16 §college-admin: all academic organizations in a tenant, for the admin Institution screen. */
    @Transactional(readOnly = true)
    public java.util.List<AcademicOrganization> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("academic_organization", UUID.randomUUID(), tenantId));
        return organizationRepository.findByTenantId(tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID campusId, UUID parentOrganizationId, String name, String type) {
        return switch (create(campusId, parentOrganizationId, name, type)) {
            case CreateResult.Success s -> s.organizationId();
            default -> throw new IllegalStateException("seed academic organization creation failed: " + name);
        };
    }

}
