package com.setu.institution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.setu.shared.academic.AcademicCalendarPolicy;
import com.setu.institution.domain.College;
import com.setu.institution.infrastructure.CollegeInsertGuard;
import com.setu.institution.infrastructure.CollegeRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.institution.domain.CollegeCreatedEvent;
import com.setu.institution.domain.CollegeUpdatedEvent;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The real, authorized college lifecycle service (Day 5 §3) — distinct from
 * {@link CollegeDirectoryService#provisionCollege}, which remains an
 * unauthenticated platform-bootstrap primitive (there is no SUPER_ADMIN to
 * grant MANAGE_PLATFORM through before the very first college/admin exist —
 * the same bootstrap problem RoleAssignmentServiceTests already documents
 * for role grants). Every mutation here goes through the Day 4
 * authorization engine and is audited; no method here bypasses either.
 * <p>
 * {@code @NamedInterface} on this class (Day 6 §13 ceremony): caller is
 * {@code enrollment.application.StudentProgramService}, which must read the
 * tenant's {@code allow_dual_program} setting
 * (docs/erd/02-academic.md's student_programs section) before accepting a
 * second {@code is_primary = true} active program for the same student.
 * Only {@link #isDualProgramAllowed} is exposed for that — a plain boolean,
 * not the settings JSON itself or the {@code College} entity. Verified via
 * {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class CollegeService {

    private static final String CREATE_PERMISSION = "MANAGE_PLATFORM";
    private static final String UPDATE_PERMISSION = "MANAGE_INSTITUTION";
    private static final String STATUS_PERMISSION = "MANAGE_PLATFORM"; // docs/state-machines.md #4: status transitions are platform-level
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final CollegeRepository collegeRepository;
    private final CollegeInsertGuard collegeInsertGuard;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final AcademicCalendarPolicy academicCalendarPolicy;
    private final ObjectMapper objectMapper;
    private final OutboxEventWriter outboxEventWriter;

    public CollegeService(CollegeRepository collegeRepository, CollegeInsertGuard collegeInsertGuard,
                           AuthorizationContext authorizationContext,
                           AuditLogWriter auditLogWriter, AcademicCalendarPolicy academicCalendarPolicy,
                           ObjectMapper objectMapper, OutboxEventWriter outboxEventWriter) {
        this.collegeRepository = collegeRepository;
        this.collegeInsertGuard = collegeInsertGuard;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.academicCalendarPolicy = academicCalendarPolicy;
        this.objectMapper = objectMapper;
        this.outboxEventWriter = outboxEventWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID collegeId) implements CreateResult {
        }

        record CodeAlreadyInUse() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(String code, String name, String timezone) {
        authorizationContext.assertCan(CREATE_PERMISSION, ResourceRef.of("college", UUID.randomUUID(), null));
        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        College college = new College(UUID.randomUUID(), code, name, timezone, now);
        try {
            collegeInsertGuard.insert(college);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.CodeAlreadyInUse();
        }
        auditLogWriter.write(college.id(), actor, "COLLEGE_CREATED", "institution.colleges", college.id(),
            Map.of("code", code, "name", name));
        outboxEventWriter.publish(new CollegeCreatedEvent(UUID.randomUUID(), now, college.id(), college.id(), UUID.randomUUID(), null,
            college.name(), college.timezone()));
        return new CreateResult.Success(college.id());
    }

    @Transactional(readOnly = true)
    public Optional<College> get(UUID collegeId) {
        Optional<College> college = collegeRepository.findById(collegeId);
        college.ifPresent(c -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("college", c.id(), c.id())));
        return college;
    }

    /**
     * Day 16 §super-admin: every college on the platform, for the Super Admin's platform dashboard — genuinely
     * platform-wide, so it cannot be scoped to any one tenant's {@code VIEW_TENANT_DATA} the way {@link #get}
     * is. Gated on {@code MANAGE_PLATFORM} (the same permission {@link #create}/{@link #changeStatus} already
     * require) since no separate "view all tenants" permission exists in the authorization matrix.
     */
    @Transactional(readOnly = true)
    public java.util.List<College> listAll() {
        authorizationContext.assertCan(CREATE_PERMISSION, ResourceRef.of("college", UUID.randomUUID(), null));
        return collegeRepository.findAll();
    }

    /**
     * Day 12 addition: a plain existence check, deliberately with NO
     * authorization of its own — the same established precedent as {@code
     * DepartmentService#findTenantId}/{@code StudentProfileService#findTenantId}
     * ("the caller has already authorized its own operation"). {@link #get}
     * requires {@code VIEW_TENANT_DATA} because it returns the full entity
     * for an authorized viewer; callers that only need to validate a
     * tenant id actually exists (e.g. finance/career creating a
     * tenant-scoped resource) must not be forced to also hold an unrelated
     * read permission just to perform that validation — a
     * {@code PLACEMENT_OFFICER} or {@code STUDENT} creating/reading their
     * own tenant-scoped resource never holds {@code VIEW_TENANT_DATA}.
     */
    @Transactional(readOnly = true)
    public boolean existsById(UUID collegeId) {
        return collegeRepository.existsById(collegeId);
    }

    public enum UpdateResult {SUCCESS, NOT_FOUND}

    @Transactional
    public UpdateResult update(UUID collegeId, String name, String timezone, String locale) {
        Optional<College> maybeCollege = collegeRepository.findById(collegeId);
        if (maybeCollege.isEmpty()) {
            return UpdateResult.NOT_FOUND;
        }
        College college = maybeCollege.get();
        authorizationContext.assertCan(UPDATE_PERMISSION, ResourceRef.of("college", college.id(), college.id()));
        UUID actor = authorizationContext.currentUserId();
        college.update(name, timezone, locale, Instant.now());
        collegeRepository.save(college);
        auditLogWriter.write(college.id(), actor, "COLLEGE_UPDATED", "institution.colleges", college.id(),
            Map.of("name", name, "timezone", timezone, "locale", locale));
        outboxEventWriter.publish(new CollegeUpdatedEvent(UUID.randomUUID(), Instant.now(), college.id(), college.id(), UUID.randomUUID(), null,
            college.name(), college.timezone()));
        return UpdateResult.SUCCESS;
    }

    public sealed interface StatusChangeResult {
        record Success() implements StatusChangeResult {
        }

        record NotFound() implements StatusChangeResult {
        }

        record InvalidTransition() implements StatusChangeResult {
        }

        /** docs/state-machines.md #4: "cannot archive a tenant with any ACTIVE academic_periods mid-term." */
        record BlockedByActiveAcademicPeriod() implements StatusChangeResult {
        }
    }

    @Transactional
    public StatusChangeResult changeStatus(UUID collegeId, College.CollegeStatus target, String reason) {
        Optional<College> maybeCollege = collegeRepository.findById(collegeId);
        if (maybeCollege.isEmpty()) {
            return new StatusChangeResult.NotFound();
        }
        College college = maybeCollege.get();
        authorizationContext.assertCan(STATUS_PERMISSION, ResourceRef.of("college", college.id(), college.id()));
        if (!college.canTransitionTo(target)) {
            return new StatusChangeResult.InvalidTransition();
        }
        if (target == College.CollegeStatus.ARCHIVED && academicCalendarPolicy.hasAnyMidTermActivePeriod(college.id())) {
            return new StatusChangeResult.BlockedByActiveAcademicPeriod();
        }
        UUID actor = authorizationContext.currentUserId();
        var from = college.status();
        college.transitionTo(target, Instant.now());
        collegeRepository.save(college);
        auditLogWriter.write(college.id(), actor, "COLLEGE_STATUS_CHANGED", "institution.colleges", college.id(),
            Map.of("from", from.name(), "to", target.name(), "reason", reason == null ? "" : reason));
        return new StatusChangeResult.Success();
    }

    public enum SettingsUpdateResult {SUCCESS, NOT_FOUND, INVALID_JSON}

    /** Closes the Day 5 carryover gap noted on College.updateSettings — settings_json had a column and default but no writer anywhere until now. */
    @Transactional
    public SettingsUpdateResult updateSettings(UUID collegeId, String settingsJson) {
        Optional<College> maybeCollege = collegeRepository.findById(collegeId);
        if (maybeCollege.isEmpty()) {
            return SettingsUpdateResult.NOT_FOUND;
        }
        College college = maybeCollege.get();
        authorizationContext.assertCan(UPDATE_PERMISSION, ResourceRef.of("college", college.id(), college.id()));
        try {
            objectMapper.readTree(settingsJson);
        } catch (Exception e) {
            return SettingsUpdateResult.INVALID_JSON;
        }
        UUID actor = authorizationContext.currentUserId();
        college.updateSettings(settingsJson, Instant.now());
        collegeRepository.save(college);
        auditLogWriter.write(college.id(), actor, "COLLEGE_UPDATED", "institution.colleges", college.id(),
            Map.of("settingsChanged", true));
        return SettingsUpdateResult.SUCCESS;
    }

    /**
     * How many terms the college's academic year has (its {@code terms_per_year} setting), 2 when it does not say. Used to turn a program's length in terms into years;
     * a college with trimesters or quarters sets it, so no semester model is hard-coded.
     */
    @Transactional(readOnly = true)
    public int termsPerYear(UUID collegeId) {
        return collegeRepository.findById(collegeId)
            .map(College::settingsJson)
            .map(json -> {
                try {
                    int n = objectMapper.readTree(json).path("terms_per_year").asInt(2);
                    return n >= 1 && n <= 12 ? n : 2;
                } catch (Exception e) {
                    return 2;
                }
            })
            .orElse(2);
    }

    /** See this class's javadoc for the full Day 6 §13 cross-module justification. */
    @Transactional(readOnly = true)
    public boolean isDualProgramAllowed(UUID collegeId) {
        return collegeRepository.findById(collegeId)
            .map(College::settingsJson)
            .map(json -> {
                try {
                    return objectMapper.readTree(json).path("allow_dual_program").asBoolean(false);
                } catch (Exception e) {
                    return false;
                }
            })
            .orElse(false);
    }

    /**
     * Day 8 §9 cross-module port: {@code enrollment.EnrollmentService} needs a
     * per-tenant per-period credit cap. No canonical value or settings_json
     * key for this exists anywhere in docs — by direct analogy to
     * {@link #isDualProgramAllowed}'s established tenant-policy-flag pattern,
     * an optional {@code max_credits_per_period} key is read; absent or
     * unparseable means no cap is enforced (never an invented arbitrary
     * number). Disclosed in the Day 8 report.
     */
    /**
     * The college's own timezone, with NO authorization check — a plain cross-module read so other modules can decide
     * "what calendar day is it for this college" (e.g. a faculty member's daily attendance). Falls back to UTC if the
     * college is missing or its stored timezone is not a valid zone id.
     */
    @Transactional(readOnly = true)
    public java.time.ZoneId timeZoneFor(UUID collegeId) {
        return collegeRepository.findById(collegeId)
            .map(College::timezone)
            .<java.time.ZoneId>map(tz -> {
                try {
                    return java.time.ZoneId.of(tz);
                } catch (Exception e) {
                    return java.time.ZoneOffset.UTC;
                }
            })
            .orElse(java.time.ZoneOffset.UTC);
    }

    @Transactional(readOnly = true)
    public Optional<java.math.BigDecimal> maxCreditsPerPeriod(UUID collegeId) {
        return collegeRepository.findById(collegeId)
            .map(College::settingsJson)
            .flatMap(json -> {
                try {
                    var node = objectMapper.readTree(json).path("max_credits_per_period");
                    return node.isMissingNode() || node.isNull() ? Optional.<java.math.BigDecimal>empty()
                        : Optional.of(new java.math.BigDecimal(node.asText()));
                } catch (Exception e) {
                    return Optional.<java.math.BigDecimal>empty();
                }
            });
    }
}
