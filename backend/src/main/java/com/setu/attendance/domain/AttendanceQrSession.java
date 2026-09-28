package com.setu.attendance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps attendance.attendance_qr_sessions (V35) — one append-only row per
 * rotation. {@code nonce} is a per-rotation, non-secret bookkeeping/audit
 * marker (rotation identity and expiry window), never the scannable secret
 * itself — the actual raw QR token is generated fresh on each rotation,
 * hashed into {@link AttendanceSession#tokenHash}, and never written to any
 * table (docs/contradiction-audit.md #9's explicit resolution). Short-lived
 * by design (the ERD flags these rows as pruning candidates once the parent
 * session closes) — this session never deletes them itself, since no
 * cleanup job is in Day 9's scope.
 */
@Entity
@Table(name = "attendance_qr_sessions", schema = "attendance")
public class AttendanceQrSession {

    @Id
    private UUID id;

    @Column(name = "attendance_session_id", nullable = false)
    private UUID attendanceSessionId;

    @Column(nullable = false)
    private String nonce;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotation_no", nullable = false)
    private int rotationNo;

    protected AttendanceQrSession() {
    }

    public AttendanceQrSession(UUID id, UUID attendanceSessionId, String nonce, Instant expiresAt, int rotationNo) {
        this.id = id;
        this.attendanceSessionId = attendanceSessionId;
        this.nonce = nonce;
        this.expiresAt = expiresAt;
        this.rotationNo = rotationNo;
    }

    public UUID id() {
        return id;
    }

    public UUID attendanceSessionId() {
        return attendanceSessionId;
    }

    public String nonce() {
        return nonce;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public int rotationNo() {
        return rotationNo;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }
}
