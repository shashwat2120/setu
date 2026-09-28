package com.setu.catalog.application;

import com.setu.catalog.domain.AcademicYear;
import com.setu.catalog.infrastructure.AcademicYearRepository;
import com.setu.catalog.infrastructure.AcademicPeriodRepository;
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

/** Academic year CRUD + lifecycle (docs/state-machines.md #5), tenant-isolated and authorized through the Day 4 engine, never a bare role check. */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class AcademicYearService {

    private static final String PERMISSION = "MANAGE_ACADEMIC_CALENDAR";
    private static final String VIEW_PERMISSION = "VIEW_TENANT_DATA";

    private final AcademicYearRepository yearRepository;
    private final AcademicPeriodRepository periodRepository;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public AcademicYearService(AcademicYearRepository yearRepository, AcademicPeriodRepository periodRepository,
                                AuthorizationContext authorizationContext, AuditLogWriter auditLogWriter) {
        this.yearRepository = yearRepository;
        this.periodRepository = periodRepository;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    @Transactional
    public UUID createYear(UUID tenantId, String code, LocalDate startDate, LocalDate endDate) {
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_year", UUID.randomUUID(), tenantId));
        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        AcademicYear year = new AcademicYear(UUID.randomUUID(), tenantId, code, startDate, endDate, actor, now);
        yearRepository.save(year);
        auditLogWriter.write(tenantId, actor, "ACADEMIC_YEAR_CREATED", "catalog.academic_years", year.id(),
            Map.of("code", code));
        return year.id();
    }

    @Transactional(readOnly = true)
    public Optional<AcademicYear> getYear(UUID yearId) {
        Optional<AcademicYear> year = yearRepository.findById(yearId);
        year.ifPresent(y -> authorizationContext.assertCan(VIEW_PERMISSION,
            ResourceRef.of("academic_year", y.id(), y.tenantId())));
        return year;
    }

    @Transactional(readOnly = true)
    public List<AcademicYear> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("academic_year", UUID.randomUUID(), tenantId));
        return yearRepository.findAll().stream().filter(y -> y.tenantId().equals(tenantId)).toList();
    }

    public enum MarkCurrentResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION}

    /** docs/state-machines.md #5: moving a year to CURRENT auto-closes whatever year was previously CURRENT for this tenant, in the same transaction — the DB's own partial-unique index would reject two CURRENT rows anyway, but this makes the "why" an explicit domain rule, not a constraint-violation surprise. */
    @Transactional
    public MarkCurrentResult markCurrent(UUID yearId) {
        Optional<AcademicYear> maybeYear = yearRepository.findById(yearId);
        if (maybeYear.isEmpty()) {
            return MarkCurrentResult.NOT_FOUND;
        }
        AcademicYear year = maybeYear.get();
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_year", year.id(), year.tenantId()));
        if (!year.canTransitionTo(AcademicYear.YearStatus.CURRENT)) {
            return MarkCurrentResult.INVALID_TRANSITION;
        }
        UUID actor = authorizationContext.currentUserId();
        Instant now = Instant.now();
        yearRepository.findByTenantIdAndStatus(year.tenantId(), AcademicYear.YearStatus.CURRENT)
            .ifPresent(previous -> {
                previous.forceCloseForSuccession(actor, now);
                yearRepository.save(previous);
                auditLogWriter.write(year.tenantId(), actor, "ACADEMIC_YEAR_STATUS_CHANGED", "catalog.academic_years",
                    previous.id(), Map.of("from", "CURRENT", "to", "CLOSED", "reason", "superseded_by_new_current_year"));
            });
        year.markCurrent(actor, now);
        yearRepository.save(year);
        auditLogWriter.write(year.tenantId(), actor, "ACADEMIC_YEAR_STATUS_CHANGED", "catalog.academic_years",
            year.id(), Map.of("from", "PLANNED", "to", "CURRENT"));
        return MarkCurrentResult.SUCCESS;
    }

    public enum CloseResult {SUCCESS, NOT_FOUND, INVALID_TRANSITION, OPEN_PERIODS_REMAIN}

    @Transactional
    public CloseResult close(UUID yearId) {
        Optional<AcademicYear> maybeYear = yearRepository.findById(yearId);
        if (maybeYear.isEmpty()) {
            return CloseResult.NOT_FOUND;
        }
        AcademicYear year = maybeYear.get();
        authorizationContext.assertCan(PERMISSION, ResourceRef.of("academic_year", year.id(), year.tenantId()));
        if (!year.canTransitionTo(AcademicYear.YearStatus.CLOSED)) {
            return CloseResult.INVALID_TRANSITION;
        }
        boolean anyPeriodStillOpen = periodRepository.findByAcademicYearId(year.id()).stream()
            .anyMatch(p -> p.status() != com.setu.catalog.domain.AcademicPeriod.PeriodStatus.CLOSED);
        if (anyPeriodStillOpen) {
            return CloseResult.OPEN_PERIODS_REMAIN;
        }
        UUID actor = authorizationContext.currentUserId();
        year.close(actor, Instant.now());
        yearRepository.save(year);
        auditLogWriter.write(year.tenantId(), actor, "ACADEMIC_YEAR_STATUS_CHANGED", "catalog.academic_years",
            year.id(), Map.of("from", "CURRENT", "to", "CLOSED"));
        return CloseResult.SUCCESS;
    }
}
