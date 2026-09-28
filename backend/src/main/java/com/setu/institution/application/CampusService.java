package com.setu.institution.application;

import com.setu.institution.domain.Campus;
import com.setu.institution.domain.College;
import com.setu.institution.infrastructure.CampusInsertGuard;
import com.setu.institution.infrastructure.CampusRepository;
import com.setu.institution.infrastructure.CollegeRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Campus CRUD (Day 5 §4) — every campus belongs to exactly one college, enforced by the FK plus an explicit existence check here so "unknown college" and "wrong tenant" fail cleanly rather than as a raw constraint violation. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class CampusService {

    private static final String MANAGE_PERMISSION = "MANAGE_INSTITUTION";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CampusRepository campusRepository;
    private final CampusInsertGuard campusInsertGuard;
    private final CollegeRepository collegeRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public CampusService(CampusRepository campusRepository, CampusInsertGuard campusInsertGuard,
                          CollegeRepository collegeRepository,
                          AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.campusRepository = campusRepository;
        this.campusInsertGuard = campusInsertGuard;
        this.collegeRepository = collegeRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID campusId) implements CreateResult {
        }

        record CollegeNotFound() implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID collegeId, String code, String name) {
        Optional<College> college = collegeRepository.findById(collegeId);
        if (college.isEmpty()) {
            return new CreateResult.CollegeNotFound();
        }
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("campus", UUID.randomUUID(), collegeId));
        UUID actor = authorizationContext.currentUserId();
        Campus campus = new Campus(UUID.randomUUID(), collegeId, code, name, actor, Instant.now());
        try {
            campusInsertGuard.insert(campus);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(collegeId, actor, "CAMPUS_CREATED", "institution.campuses", campus.id(),
            Map.of("code", code, "name", name));
        return new CreateResult.Success(campus.id());
    }

    @Transactional(readOnly = true)
    public Optional<Campus> get(UUID campusId) {
        Optional<Campus> campus = campusRepository.findById(campusId);
        campus.ifPresent(c -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("campus", c.id(), c.tenantId())));
        return campus;
    }

    @Transactional(readOnly = true)
    public List<Campus> listForCollege(UUID collegeId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("campus", UUID.randomUUID(), collegeId));
        return campusRepository.findByTenantId(collegeId);
    }

    public enum UpdateResult {SUCCESS, NOT_FOUND}

    @Transactional
    public UpdateResult update(UUID campusId, String name) {
        Optional<Campus> maybeCampus = campusRepository.findById(campusId);
        if (maybeCampus.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        Campus campus = maybeCampus.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("campus", campus.id(), campus.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        campus.update(name, actor, Instant.now());
        campusRepository.save(campus);
        auditLogWriter.write(campus.tenantId(), actor, "CAMPUS_UPDATED", "institution.campuses", campus.id(),
            Map.of("name", name));
        return UpdateResult.SUCCESS;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create} — see this class's own javadoc note on why a nested sealed-interface {@code Success} record cannot be referenced across the module boundary directly. */
    @Transactional
    public UUID createForSeed(UUID collegeId, String code, String name) {
        return switch (create(collegeId, code, name)) {
            case CreateResult.Success s -> s.campusId();
            default -> throw new IllegalStateException("seed campus creation failed: " + code);
        };
    }

}
