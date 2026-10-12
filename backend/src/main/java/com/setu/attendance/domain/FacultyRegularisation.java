package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps attendance.faculty_attendance_regularisations (V74): a faculty member's request to correct a past day of their own
 * attendance (typically "I forgot to check in"), decided by an administrator. State machine: docs/state-machines.md #26.
 * Terminal states are final; only a PENDING request can be decided or withdrawn. WHO may do each step is decided by the
 * service, never here.
 */
@Entity
@Table(name = "faculty_attendance_regularisations", schema = "attendance")
public class FacultyRegularisation {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "faculty_id", nullable = false)
    private UUID facultyId;

    @Column(name = "attendance_date", nullable = false)
    private LocalDate attendanceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_status", nullable = false)
    private FacultyAttendanceStatus requestedStatus;

    @Column(name = "requested_check_in_at")
    private Instant requestedCheckInAt;

    @Column(name = "requested_check_out_at")
    private Instant requestedCheckOutAt;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RegularisationStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected FacultyRegularisation() {
    }

    public FacultyRegularisation(UUID tenantId, UUID facultyId, LocalDate attendanceDate,
                                  FacultyAttendanceStatus requestedStatus, Instant requestedCheckInAt,
                                  Instant requestedCheckOutAt, String reason, Instant now) {
        if (requestedStatus == null || requestedStatus == FacultyAttendanceStatus.ABSENT) {
            throw new IllegalArgumentException("a regularisation can only ask for PRESENT, HALF_DAY or ON_LEAVE");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a reason is required");
        }
        if (!requestedStatus.hasWorkTimes() && (requestedCheckInAt != null || requestedCheckOutAt != null)) {
            throw new IllegalArgumentException("check-in/check-out times only apply to PRESENT or HALF_DAY");
        }
        if (requestedCheckOutAt != null && requestedCheckInAt == null) {
            throw new IllegalArgumentException("a check-out time needs a check-in time");
        }
        if (requestedCheckOutAt != null && !requestedCheckOutAt.isAfter(requestedCheckInAt)) {
            throw new IllegalArgumentException("check-out must be after check-in");
        }
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.facultyId = facultyId;
        this.attendanceDate = attendanceDate;
        this.requestedStatus = requestedStatus;
        this.requestedCheckInAt = requestedCheckInAt;
        this.requestedCheckOutAt = requestedCheckOutAt;
        this.reason = reason.trim();
        this.status = RegularisationStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public boolean isPending() {
        return status == RegularisationStatus.PENDING;
    }

    public void approve(UUID decidedBy, String note, Instant now) {
        requirePending();
        this.status = RegularisationStatus.APPROVED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim();
        this.updatedAt = now;
    }

    public void reject(UUID decidedBy, String note, Instant now) {
        requirePending();
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("a note is required to reject a request");
        }
        this.status = RegularisationStatus.REJECTED;
        this.decidedBy = decidedBy;
        this.decidedAt = now;
        this.decisionNote = note.trim();
        this.updatedAt = now;
    }

    public void withdraw(Instant now) {
        requirePending();
        this.status = RegularisationStatus.WITHDRAWN;
        this.updatedAt = now;
    }

    private void requirePending() {
        if (status != RegularisationStatus.PENDING) {
            throw new IllegalStateException("only a PENDING request can be decided or withdrawn (this one is " + status + ")");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID facultyId() {
        return facultyId;
    }

    public LocalDate attendanceDate() {
        return attendanceDate;
    }

    public FacultyAttendanceStatus requestedStatus() {
        return requestedStatus;
    }

    public Instant requestedCheckInAt() {
        return requestedCheckInAt;
    }

    public Instant requestedCheckOutAt() {
        return requestedCheckOutAt;
    }

    public String reason() {
        return reason;
    }

    public RegularisationStatus status() {
        return status;
    }

    public UUID decidedBy() {
        return decidedBy;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    public String decisionNote() {
        return decisionNote;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public int version() {
        return version;
    }
}
