package com.setu.career.application;

import com.setu.career.domain.PlacementRecord;
import com.setu.career.domain.PlacementRecordedEvent;
import com.setu.career.infrastructure.PlacementRecordRepository;
import com.setu.directory.application.StudentProfileService;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.governance.AuditLogWriter;
import com.setu.shared.governance.OutboxEventWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Day 12 §Career: placement_records — see {@link PlacementRecord}'s javadoc
 * for why this is append-only (never updated). Placement-officer managed
 * (write: {@code MANAGE_PLACEMENT_RECORDS}); readable by the owning
 * student or {@code VIEW_PLACEMENT_RECORDS} (admin/placement-officer).
 * Emits {@link PlacementRecordedEvent} to the outbox in the same
 * transaction as the insert, matching docs/kafka-event-catalog.md's
 * {@code PlacementRecorded} row.
 */
@org.springframework.modulith.NamedInterface // Day 15 §demo-data: com.setu.shared.seed.DemoDataSeeder needs this cross-module
@Service
public class PlacementRecordService {

    private static final String MANAGE_PERMISSION = "MANAGE_PLACEMENT_RECORDS";
    private static final String VIEW_PERMISSION = "VIEW_PLACEMENT_RECORDS";

    private final PlacementRecordRepository placementRecordRepository;
    private final StudentProfileService studentProfileService;
    private final CareerAccessSupport accessSupport;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;
    private final OutboxEventWriter outboxEventWriter;

    public PlacementRecordService(PlacementRecordRepository placementRecordRepository, StudentProfileService studentProfileService,
                                   CareerAccessSupport accessSupport, AuthorizationContext authorizationContext,
                                   AuditLogWriter auditLogWriter, OutboxEventWriter outboxEventWriter) {
        this.placementRecordRepository = placementRecordRepository;
        this.studentProfileService = studentProfileService;
        this.accessSupport = accessSupport;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
        this.outboxEventWriter = outboxEventWriter;
    }

    public sealed interface RecordResult {
        record Success(UUID placementId) implements RecordResult {
        }

        record StudentNotFound() implements RecordResult {
        }

        record InvalidData(String message) implements RecordResult {
        }
    }

    @Transactional
    public RecordResult record(UUID studentId, UUID jobId, String employer, String role, BigDecimal ctc, LocalDate placedAt) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return new RecordResult.StudentNotFound();
        }
        UUID tenantId = studentInfo.get().tenantId();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("placement_record", UUID.randomUUID(), tenantId));
        return create(tenantId, studentId, jobId, employer, role, ctc, placedAt, null);
    }

    /**
     * The placement that follows from selecting a student for a job: the employer and role are the job's, the package is what the offer says, the placement is dated today and
     * the student joins on {@code joiningDate} (null when not known). No authorization of its own: the officer has been authorized to make the decision this follows from, and
     * this runs in that decision's transaction, so a selection and its placement are made together or not at all.
     */
    @Transactional
    RecordResult recordFromSelection(UUID tenantId, UUID studentId, UUID jobId, String employer, String role, BigDecimal ctc, LocalDate joiningDate) {
        return create(tenantId, studentId, jobId, employer, role, ctc, LocalDate.now(), joiningDate);
    }

    private RecordResult create(UUID tenantId, UUID studentId, UUID jobId, String employer, String role, BigDecimal ctc, LocalDate placedAt, LocalDate joiningDate) {
        PlacementRecord placement;
        try {
            placement = new PlacementRecord(UUID.randomUUID(), tenantId, studentId, jobId, employer, role, ctc, placedAt);
        } catch (IllegalArgumentException e) {
            return new RecordResult.InvalidData(e.getMessage());
        }
        placement.joiningOn(joiningDate);
        placementRecordRepository.saveAndFlush(placement);

        UUID correlationId = UUID.randomUUID();
        var event = new PlacementRecordedEvent(UUID.randomUUID(), Instant.now(), tenantId, studentId, correlationId,
            null, placement.id(), studentId, employer);
        outboxEventWriter.write("placement_records", studentId, event.eventType(), event.eventVersion(), event,
            tenantId, correlationId, null);
        auditLogWriter.write(tenantId, authorizationContext.currentUserId(), "PLACEMENT_RECORDED",
            "career.placement_records", placement.id(), Map.of("studentId", studentId, "employer", employer));
        return new RecordResult.Success(placement.id());
    }

    /** Own (student) or VIEW_PLACEMENT_RECORDS (admin/placement-officer). */
    @Transactional(readOnly = true)
    public List<PlacementRecord> listForStudent(UUID studentId) {
        Optional<StudentProfileService.StudentTenantOwner> studentInfo = studentProfileService.findTenantAndOwnerUserId(studentId);
        if (studentInfo.isEmpty()) {
            return List.of();
        }
        UUID tenantId = studentInfo.get().tenantId();
        Optional<UUID> ownStudentId = accessSupport.currentStudentId(tenantId);
        if (ownStudentId.isEmpty() || !ownStudentId.get().equals(studentId)) {
            authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("placement_record", studentId, tenantId));
        }
        return placementRecordRepository.findByStudentId(studentId);
    }

    /** Day 16 §placement: tenant-wide listing for the Placement Officer overview — admin/placement-officer only, never a self-view path (a student sees their own via {@link #listForStudent}). */
    @Transactional(readOnly = true)
    public List<PlacementRecord> listForTenant(UUID tenantId) {
        authorizationContext.assertCan(VIEW_PERMISSION, ResourceRef.of("placement_record", UUID.randomUUID(), tenantId));
        return placementRecordRepository.findByTenantId(tenantId);
    }
}
