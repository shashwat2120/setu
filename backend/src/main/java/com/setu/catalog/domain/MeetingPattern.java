package com.setu.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Maps catalog.meeting_patterns (V29). Room-overlap prevention is DB-enforced
 * via {@code excl_meeting_patterns_room_overlap} (an EXCLUDE USING gist
 * constraint on room_id + day_of_week + time_range) — this entity's own
 * validation is defense in depth (a clean typed error before the DB
 * constraint would otherwise fire), not a duplicate implementation of the
 * overlap logic itself. See V29's own header comment for why faculty-level
 * overlap is explicitly NOT covered by this or any DB constraint (faculty
 * isn't a column on this table).
 */
@Entity
@Table(name = "meeting_patterns", schema = "catalog")
public class MeetingPattern {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "room_id", nullable = false)
    private UUID roomId;

    @Column(name = "effective_from")
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    protected MeetingPattern() {
    }

    public MeetingPattern(UUID id, UUID tenantId, UUID sectionId, short dayOfWeek, LocalTime startTime,
                           LocalTime endTime, UUID roomId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw new IllegalArgumentException("day_of_week must be between 0 and 6");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("end_time must be after start_time");
        }
        if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effective_to must be after effective_from");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.sectionId = sectionId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.roomId = roomId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
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

    public UUID roomId() {
        return roomId;
    }

    public short dayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime startTime() {
        return startTime;
    }

    public LocalTime endTime() {
        return endTime;
    }

    public LocalDate effectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate effectiveTo() {
        return effectiveTo;
    }

    /** Room/time fields may change (an audited correction); {@code sectionId} never does — a meeting pattern for a different section is a different row, not an edit. */
    public void update(short dayOfWeek, LocalTime startTime, LocalTime endTime, UUID roomId, LocalDate effectiveFrom,
                        LocalDate effectiveTo) {
        if (dayOfWeek < 0 || dayOfWeek > 6) {
            throw new IllegalArgumentException("day_of_week must be between 0 and 6");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("end_time must be after start_time");
        }
        if (effectiveFrom != null && effectiveTo != null && !effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effective_to must be after effective_from");
        }
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.roomId = roomId;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
    }
}
