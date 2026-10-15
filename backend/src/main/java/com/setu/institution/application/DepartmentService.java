package com.setu.institution.application;

import com.setu.shared.identity.TenantMembership;
import com.setu.institution.domain.AcademicOrganization;
import com.setu.institution.domain.Department;
import com.setu.institution.infrastructure.AcademicOrganizationRepository;
import com.setu.institution.infrastructure.DepartmentInsertGuard;
import com.setu.institution.infrastructure.DepartmentRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Department CRUD (Day 5 §6) — belongs to an academic organization, HOD
 * reference validated against identity before being accepted, never
 * trusted blind.
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony):
 * <ol>
 *   <li>Caller: {@code catalog.application.ProgramService}, when creating a
 *       program — it must validate the supplied department actually exists
 *       and belongs to the SAME tenant as the program being created, rather
 *       than trusting a client-supplied department id at face value.</li>
 *   <li>Contract consumed: only {@link #findTenantId}, a plain existence +
 *       tenant lookup — not the whole class, not the {@code Department}
 *       entity, not {@link #create}/{@link #assignHod}/{@link #get}.</li>
 *   <li>{@code findTenantId} deliberately does no authorization check of
 *       its own and returns a bare {@code UUID}, not the entity — the
 *       CALLER (ProgramService) has already authorized its own operation
 *       against the program's own tenant before reaching here; a second,
 *       redundant permission check inside this lookup would just be
 *       another place for the two to drift out of sync, and returning the
 *       full entity would leak institution-internal fields catalog has no
 *       business seeing.</li>
 *   <li>Verified: {@code ModularityTests} passes with this dependency in
 *       place (see the Day 6 report).</li>
 *   <li>Why this dependency exists at all: a program's department reference
 *       crosses from catalog into institution-owned data (Day 6 §13 lists
 *       "program -&gt; department" explicitly as a dependency to handle
 *       carefully) — there is no way to validate it without SOME call into
 *       institution, and importing {@code institution.infrastructure.
 *       DepartmentRepository} directly would violate the module boundary
 *       this whole mechanism exists to enforce.</li>
 * </ol>
 */
@org.springframework.modulith.NamedInterface
@Service
public class DepartmentService {

    private static final String MANAGE_PERMISSION = "MANAGE_INSTITUTION";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final DepartmentRepository departmentRepository;
    private final DepartmentInsertGuard departmentInsertGuard;
    private final AcademicOrganizationRepository organizationRepository;
    private final TenantMembership tenantMembership;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public DepartmentService(DepartmentRepository departmentRepository, DepartmentInsertGuard departmentInsertGuard,
                              AcademicOrganizationRepository organizationRepository,
                              TenantMembership tenantMembership, AuthorizationContext authorizationContext,
                              AuditLogWriter auditLogWriter) {
        this.departmentRepository = departmentRepository;
        this.departmentInsertGuard = departmentInsertGuard;
        this.organizationRepository = organizationRepository;
        this.tenantMembership = tenantMembership;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID departmentId) implements CreateResult {
        }

        record OrganizationNotFound() implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID organizationId, String code, String name) {
        Optional<AcademicOrganization> organization = organizationRepository.findById(organizationId);
        if (organization.isEmpty()) {
            return new CreateResult.OrganizationNotFound();
        }
        UUID tenantId = organization.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("department", UUID.randomUUID(), tenantId));

        UUID actor = authorizationContext.currentUserId();
        Department department = new Department(UUID.randomUUID(), tenantId, organizationId, code, name, actor, Instant.now());
        try {
            departmentInsertGuard.insert(department);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(tenantId, actor, "DEPARTMENT_CREATED", "institution.departments", department.id(),
            Map.of("code", code, "name", name));
        return new CreateResult.Success(department.id());
    }

    public sealed interface AssignHodResult {
        record Success() implements AssignHodResult {
        }

        record NotFound() implements AssignHodResult {
        }

        /** Day 5 §6: "do not automatically create HOD relationships without verifying that the referenced identity belongs to the same authorized college." */
        record HodNotAuthorizedInTenant() implements AssignHodResult {
        }
    }

    @Transactional
    public AssignHodResult assignHod(UUID departmentId, UUID hodUserId) {
        Optional<Department> maybeDepartment = departmentRepository.findById(departmentId);
        if (maybeDepartment.isEmpty()) {
            return new AssignHodResult.NotFound();
        }
        Department department = maybeDepartment.get();
        authorizationContext.assertCan(MANAGE_PERMISSION,
            ResourceRef.of("department", department.id(), department.tenantId()));

        if (!tenantMembership.hasActiveRoleInTenant(hodUserId, department.tenantId())) {
            return new AssignHodResult.HodNotAuthorizedInTenant();
        }

        UUID actor = authorizationContext.currentUserId();
        department.assignHod(hodUserId, actor, Instant.now());
        departmentRepository.save(department);
        auditLogWriter.write(department.tenantId(), actor, "DEPARTMENT_HOD_ASSIGNED", "institution.departments",
            department.id(), Map.of("hodUserId", hodUserId));
        return new AssignHodResult.Success();
    }

    @Transactional(readOnly = true)
    public Optional<Department> get(UUID departmentId) {
        Optional<Department> department = departmentRepository.findById(departmentId);
        department.ifPresent(d -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("department", d.id(), d.tenantId())));
        return department;
    }

    /** Day 16 §college-admin: all departments in a tenant, for the admin Institution/Departments screen. */
    @Transactional(readOnly = true)
    public java.util.List<Department> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("department", UUID.randomUUID(), tenantId));
        return departmentRepository.findByTenantId(tenantId);
    }

    /** See this class's javadoc for the full Day 6 §13 cross-module justification. */
    @Transactional(readOnly = true)
    public Optional<UUID> findTenantId(UUID departmentId) {
        return departmentRepository.findById(departmentId).map(Department::tenantId);
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID organizationId, String code, String name) {
        return switch (create(organizationId, code, name)) {
            case CreateResult.Success s -> s.departmentId();
            default -> throw new IllegalStateException("seed department creation failed: " + code);
        };
    }

}
