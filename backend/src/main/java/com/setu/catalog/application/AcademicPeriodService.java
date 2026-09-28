package com.setu.catalog.application;

import com.setu.catalog.domain.AcademicPeriod;
import com.setu.catalog.domain.AcademicYear;
import com.setu.catalog.infrastructure.AcademicPeriodRepository;
import com.setu.catalog.infrastructure.AcademicYearRepository;
import com.setu.shared.academic.AcademicCalendarPolicy;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Academic period CRUD + lifecycle (docs/state-machines.md #6). Deliberately
 * no "semester" vocabulary anywhere — code/name are free text (Day 5
 * instruction §8).
 * <p>
 * Consumed two different ways by two different callers:
 * {@link #hasAnyMidTermActivePeriod} is called cross-module by
 * institution.CollegeService, but through the shared
 * {@link AcademicCalendarPolicy} contract this class implements, not a
 * direct reference to this class — see that interface's package-info for
 * why (breaking a genuine institution&nbsp;&#8596;&nbsp;catalog dependency
 * cycle {@code ApplicationModules.verify()} caught, Day 6). Separately,
 * {@code @NamedInterface} is added at the class level (Day 7 §3 ceremony)
 * because {@code enrollment.application.CourseSectionService} needs
 * {@link #findTenantAndWindow} to validate a section's tenant and
 * registration-window bounds against its period — a different caller, a
 * different method, and (unlike the institution case) no reverse edge from
 * catalog back into enrollment exists, so the plain
 * {@code @NamedInterface} pattern already used by
 * {@code catalog.ProgramService}/{@code catalog.CourseService} applies
 * directly; no shared-interface indirection is needed for this one.
 * Verified via {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class AcademicPeriodService implements AcademicCalendarPolicy {

    private static final String PERMISSION = "MANAGE_ACADEMIC_CALENDAR";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AcademicPeriodRepository periodRepository;
    private final AcademicYearRepository yearRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AcademicPeriodService(AcademicPeriodRepository periodRepository, AcademicYearRepository yearRepository,
                                  AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.periodRepository = periodRepository;
        this.yearRepository = yearRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID periodId) implements CreateResult {
        }

        record AcademicYearNotFound() implements CreateResult {
        }

        /** Day 5 instruction §8: "validate period boundaries against the parent academic year." */
        record OutsideAcademicYearBounds() implements CreateResult {
        }

        /** Day 5 instruction §11: "registration window validity" — covers both a bad date range (end &lt;= start) and a bad registration window (close &lt;= open), the same two checks AcademicPeriod's own constructor enforces. */
        record InvalidPeriodData(String message) implements CreateResult {
        }
    }

    @Transactional
    public CreateResult createPeriod(UUID academicYearId, String code, String name, int sequence,
                                      LocalDate startDate, LocalDate endDate, Instant registrationOpenAt,
                                      Instant registrationCloseAt) {
        Optional<AcademicYear> maybeYear = yearRepository.findById(academicYearId);
        if (maybeYear.isEmpty()) {
            return new CreateResult.AcademicYearNotFound();
        }
        AcademicYear year = maybeYear.get();
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_period", UUID.randomUUID(), year.tenantId()));

        if (startDate.isBefore(year.startDate()) || endDate.isAfter(year.endDate())) {
            return new CreateResult.OutsideAcademicYearBounds();
        }

        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        AcademicPeriod period;
        try {
            period = new AcademicPeriod(UUID.randomUUID(), year.tenantId(), year.id(), code, name,
                sequence, startDate, endDate, registrationOpenAt, registrationCloseAt, actor, now);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidPeriodData(e.getMessage());
        }
        periodRepository.save(period);
        auditLogWriter.write(year.tenantId(), actor, "ACADEMIC_PERIOD_CREATED", "catalog.academic_periods",
            period.id(), Map.of("code", code, "academicYearId", academicYearId));
        return new CreateResult.Success(period.id());
    }

    /** Day 16 §college-admin: every period under one academic year, for the admin Institution screen. */
    @Transactional(readOnly = true)
    public List<AcademicPeriod> listForYear(UUID academicYearId) {
        Optional<AcademicYear> maybeYear = yearRepository.findById(academicYearId);
        if (maybeYear.isEmpty()) {
            return List.of();
        }
        AcademicYear year = maybeYear.get();
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("academic_period", UUID.randomUUID(), year.tenantId()));
        return periodRepository.findByAcademicYearId(academicYearId);
    }

    @Transactional(readOnly = true)
    public Optional<AcademicPeriod> getPeriod(UUID periodId) {
        Optional<AcademicPeriod> period = periodRepository.findById(periodId);
        period.ifPresent(p -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("academic_period", p.id(), p.tenantId())));
        return period;
    }

    public enum TransitionResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    @Transactional
    public TransitionResult transitionTo(UUID periodId, AcademicPeriod.PeriodStatus target) {
        Optional<AcademicPeriod> maybePeriod = periodRepository.findById(periodId);
        if (maybePeriod.isEmpty()) {
            return TransitionResult.NOT_FOUND;
        }
        AcademicPeriod period = maybePeriod.get();
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_period", period.id(), period.tenantId()));
        if (!period.canTransitionTo(target)) {
            return TransitionResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        var from = period.status();
        period.transitionTo(target, actor, Instant.now());
        periodRepository.save(period);
        auditLogWriter.write(period.tenantId(), actor, "ACADEMIC_PERIOD_STATUS_CHANGED", "catalog.academic_periods",
            period.id(), Map.of("from", from.name(), "to", target.name()));
        return TransitionResult.SUCCESS;
    }

    public enum RegistrationWindowResult {SUCCESS, NOT_FOUND, INVALID_WINDOW}

    @Transactional
    public RegistrationWindowResult updateRegistrationWindow(UUID periodId, Instant openAt, Instant closeAt) {
        Optional<AcademicPeriod> maybePeriod = periodRepository.findById(periodId);
        if (maybePeriod.isEmpty()) {
            return RegistrationWindowResult.NOT_FOUND;
        }
        AcademicPeriod period = maybePeriod.get();
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_period", period.id(), period.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        try {
            period.updateRegistrationWindow(openAt, closeAt, actor, Instant.now());
        } catch (IllegalArgumentException e) {
            return RegistrationWindowResult.INVALID_WINDOW;
        }
        periodRepository.save(period);
        auditLogWriter.write(period.tenantId(), actor, "ACADEMIC_PERIOD_REGISTRATION_WINDOW_CHANGED",
            "catalog.academic_periods", period.id(),
            Map.of("registrationOpenAt", String.valueOf(openAt), "registrationCloseAt", String.valueOf(closeAt)));
        return RegistrationWindowResult.SUCCESS;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasAnyMidTermActivePeriod(UUID tenantId) {
        return periodRepository.findByTenantId(tenantId).stream().anyMatch(AcademicPeriod::isMidTermActive);
    }

    /** See this class's javadoc for the full Day 7 §3 cross-module justification. */
    public record PeriodTenantWindow(UUID tenantId, Instant registrationOpenAt, Instant registrationCloseAt) {
    }

    @Transactional(readOnly = true)
    public Optional<PeriodTenantWindow> findTenantAndWindow(UUID periodId) {
        return periodRepository.findById(periodId)
            .map(p -> new PeriodTenantWindow(p.tenantId(), p.registrationOpenAt(), p.registrationCloseAt()));
    }

    /**
     * Day 8 §1 cross-module port: {@code enrollment.EnrollmentService}'s
     * invariant chain needs "period open for registration"
     * ({@code status == REGISTRATION_OPEN}), which {@link #findTenantAndWindow}
     * doesn't expose. A separate method/record rather than widening that one,
     * so the existing Day 7 caller ({@code enrollment.CourseSectionService})
     * is untouched.
     */
    public record PeriodRegistrationInfo(UUID tenantId, AcademicPeriod.PeriodStatus status) {
    }

    @Transactional(readOnly = true)
    public Optional<PeriodRegistrationInfo> findRegistrationInfo(UUID periodId) {
        return periodRepository.findById(periodId).map(p -> new PeriodRegistrationInfo(p.tenantId(), p.status()));
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #createPeriod}. */
    @Transactional
    public UUID createPeriodForSeed(UUID academicYearId, String code, String name, int sequence,
                                     java.time.LocalDate startDate, java.time.LocalDate endDate,
                                     java.time.Instant registrationOpenAt, java.time.Instant registrationCloseAt) {
        return switch (createPeriod(academicYearId, code, name, sequence, startDate, endDate, registrationOpenAt, registrationCloseAt)) {
            case CreateResult.Success s -> s.periodId();
            default -> throw new IllegalStateException("seed academic period creation failed: " + code);
        };
    }

}
