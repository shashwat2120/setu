package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps attendance.attendance_sessions (V34) — a faculty-opened, short-lived
 * attendance window for one class meeting. Transitions per
 * docs/state-machines.md #14: {@code OPEN -> CLOSED} only, by the opening
 * faculty or automatically at {@code ends_at}; terminal, never reopened
 * (late/manual marks are {@code attendance_records} corrections against the
 * closed session, not a reopen). {@code token_hash} is a hash of the
 * CURRENT rotating token only (docs/contradiction-audit.md #9) — the raw
 * token itself is never a field on this entity.
 */
@Entity
@Table(name = "attendance_sessions", schema = "attendance")
public class AttendanceSession {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "opened_by", nullable = false)
    private UUID openedBy;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttendanceSessionStatus status;

    @Version
    private int version;

    protected AttendanceSession() {
    }

    public AttendanceSession(UUID id, UUID tenantId, UUID sectionId, UUID meetingId, UUID openedBy, Instant startedAt,
                              Instant endsAt, String tokenHash) {
        if (!endsAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("ends_at must be after started_at");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.meetingId = meetingId;
        this.openedBy = openedBy;
        this.startedAt = startedAt;
        this.endsAt = endsAt;
        this.tokenHash = tokenHash;
        this.status = AttendanceSessionStatus.OPEN;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID sectionId() {
        return sectionId;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public UUID openedBy() {
        return openedBy;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant endsAt() {
        return endsAt;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public AttendanceSessionStatus status() {
        return status;
    }

    public boolean isOpen() {
        return status == AttendanceSessionStatus.OPEN;
    }

    /** A new rotation's hash replaces the previous one — only meaningful while the session is still OPEN. */
    public void rotateToken(String newTokenHash) {
        if (!isOpen()) {
            throw new IllegalStateException("Cannot rotate the QR token of a CLOSED attendance session");
        }
        this.tokenHash = newTokenHash;
    }

    public boolean canTransitionTo(AttendanceSessionStatus target) {
        return status == AttendanceSessionStatus.OPEN && target == AttendanceSessionStatus.CLOSED;
    }

    public void transitionTo(AttendanceSessionStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Cannot transition attendance session from " + status + " to " + target);
        }
        this.status = target;
    }
}
