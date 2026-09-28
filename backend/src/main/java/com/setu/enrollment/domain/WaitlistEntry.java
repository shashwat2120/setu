package com.setu.enrollment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps enrollment.waitlist_entries (V33). Position assignment and
 * re-compaction safety are enforced by the caller (EnrollmentService),
 * which always holds the owning course_sections row's FOR UPDATE lock
 * before touching any waitlist row for that section — see that class's
 * javadoc for the full locking strategy.
 */
@Entity
@Table(name = "waitlist_entries", schema = "enrollment")
public class WaitlistEntry {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WaitlistEntry() {
    }

    public WaitlistEntry(UUID id, UUID tenantId, UUID sectionId, UUID studentId, int position, Instant createdAt) {
        if (position <= 0) {
            throw new IllegalArgumentException("position must be positive");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.studentId = studentId;
        this.position = position;
        this.status = WaitlistStatus.WAITING;
        this.createdAt = createdAt;
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

    public UUID studentId() {
        return studentId;
    }

    public int position() {
        return position;
    }

    public WaitlistStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** Re-compaction after another entry ahead of this one leaves the list — see docs/state-machines.md #12's "no gaps" validation rule. */
    public void decrementPosition() {
        this.position = this.position - 1;
    }

    /** Direct WAITING -&gt; CONVERTED auto-promotion — see WaitlistStatus's javadoc for why OFFERED's time-boxed window isn't implemented this day. */
    public void convert() {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException("Cannot convert waitlist entry in status " + status);
        }
        this.status = WaitlistStatus.CONVERTED;
    }

    public void withdraw() {
        if (status != WaitlistStatus.WAITING) {
            throw new IllegalStateException("Cannot withdraw waitlist entry in status " + status);
        }
        this.status = WaitlistStatus.WITHDRAWN;
    }

    /** Reopens a WITHDRAWN/EXPIRED/CONVERTED row for a fresh wait — the composite unique (section_id, student_id) constraint means a returning student's second wait reuses this row rather than a second insert, the same reopen pattern as {@link Enrollment#reopen}. */
    public void reactivate(int newPosition, Instant createdAt) {
        if (status == WaitlistStatus.WAITING) {
            throw new IllegalStateException("Waitlist entry is already WAITING");
        }
        this.status = WaitlistStatus.WAITING;
        this.position = newPosition;
        this.createdAt = createdAt;
    }
}
