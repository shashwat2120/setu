package com.setu.learning.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps learning.learning_progress (V37/V38, {@code version} added V64).
 * Evidence, not a grade (ERD note) — this entity never computes or feeds a
 * grades row itself. {@code (lesson_id, student_id)} is DB-unique; only the
 * owning student may write it (Day 10 §authorization matrix: FACULTY gets
 * read-only here, unlike attendance where faculty marks/corrects).
 * <p>
 * State/percent/timestamp coherence is enforced here, not left to the
 * caller: {@code NOT_STARTED} carries no percent and no timestamps;
 * {@code IN_PROGRESS} requires {@code 0 <= percent < 100} and a
 * {@code startedAt} (set once, on the first non-NOT_STARTED write, never
 * overwritten after); {@code COMPLETED} requires {@code percent == 100} and
 * sets {@code completedAt} once (idempotent on repeat completion calls —
 * the original completion timestamp is never re-stamped).
 */
@Entity
@Table(name = "learning_progress", schema = "learning")
public class LearningProgress {

    @Id
    private UUID id;

    @Column(name = "lesson_id", nullable = false)
    private UUID lessonId;

    @Column(name = "student_id", nullable = false)
    private UUID studentId;

    @Enumerated(EnumType.STRING)
    private LearningProgressState state;

    private BigDecimal percent;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "last_position", columnDefinition = "jsonb")
    private String lastPosition;

    @Version
    private int version;

    protected LearningProgress() {
    }

    public LearningProgress(UUID id, UUID lessonId, UUID studentId) {
        this.id = id;
        this.lessonId = lessonId;
        this.studentId = studentId;
        this.state = LearningProgressState.NOT_STARTED;
    }

    public UUID id() {
        return id;
    }

    public UUID lessonId() {
        return lessonId;
    }

    public UUID studentId() {
        return studentId;
    }

    public LearningProgressState state() {
        return state;
    }

    public BigDecimal percent() {
        return percent;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public String lastPosition() {
        return lastPosition;
    }

    public int version() {
        return version;
    }

    /** Throws {@link IllegalArgumentException} on any state/percent/timestamp incoherence — see class javadoc. Only ever called on a brand-new, not-yet-persisted instance (the first write for a (lesson, student) pair); see {@code computeTimestamps} for the update path, which never mutates a Hibernate-managed instance (Day 9's shared-persistence-context lesson). */
    public void record(LearningProgressState newState, BigDecimal newPercent, String lastPosition, Instant now) {
        validatePercent(newState, newPercent);
        Timestamps ts = computeTimestamps(this.state, this.startedAt, this.completedAt, newState, now);
        this.startedAt = ts.startedAt();
        this.completedAt = ts.completedAt();
        this.state = newState;
        this.percent = newPercent;
        this.lastPosition = lastPosition;
    }

    public record Timestamps(Instant startedAt, Instant completedAt) {
    }

    /**
     * Side-effect-free — computes what {@code started_at}/{@code
     * completed_at} should become given the row's CURRENT persisted values
     * and the incoming write, without touching any Hibernate-managed
     * instance. {@link LearningProgressService} calls this directly for the
     * version-checked conditional-UPDATE path (an existing row), passing
     * plain values read from (but never mutated on) the managed entity —
     * exactly the entity-sharing pitfall {@code AttendanceRecordInsertGuard}
     * documents from Day 9.
     */
    public static Timestamps computeTimestamps(LearningProgressState currentState, Instant currentStartedAt,
                                                Instant currentCompletedAt, LearningProgressState newState, Instant now) {
        Instant startedAt = currentStartedAt;
        if (currentState == LearningProgressState.NOT_STARTED && newState != LearningProgressState.NOT_STARTED
            && startedAt == null) {
            startedAt = now;
        }
        Instant completedAt = currentCompletedAt;
        if (newState == LearningProgressState.COMPLETED && completedAt == null) {
            completedAt = now;
        }
        return new Timestamps(startedAt, completedAt);
    }

    public static void validatePercent(LearningProgressState newState, BigDecimal newPercent) {
        switch (newState) {
            case NOT_STARTED -> {
                if (newPercent != null && newPercent.compareTo(BigDecimal.ZERO) != 0) {
                    throw new IllegalArgumentException("NOT_STARTED must not carry a nonzero percent");
                }
            }
            case IN_PROGRESS -> {
                if (newPercent == null || newPercent.compareTo(BigDecimal.ZERO) < 0
                    || newPercent.compareTo(new BigDecimal("100")) >= 0) {
                    throw new IllegalArgumentException("IN_PROGRESS requires 0 <= percent < 100");
                }
            }
            case COMPLETED -> {
                if (newPercent == null || newPercent.compareTo(new BigDecimal("100")) != 0) {
                    throw new IllegalArgumentException("COMPLETED requires percent == 100");
                }
            }
        }
    }
}
