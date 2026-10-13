package com.setu.catalog.application;

import com.setu.catalog.domain.MeetingPattern;
import com.setu.catalog.domain.Room;
import com.setu.catalog.infrastructure.MeetingPatternRepository;
import com.setu.catalog.infrastructure.MeetingPatternWriteGuard;
import com.setu.catalog.infrastructure.RoomRepository;
import com.setu.shared.authorization.AuthorizationContext;
import com.setu.shared.authorization.ResourceRef;
import com.setu.shared.enrollment.CourseSectionDirectory;
import com.setu.shared.governance.AuditLogWriter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Room meeting patterns for course sections (Day 7 §12). Room-overlap
 * prevention is DB-enforced ({@code excl_meeting_patterns_room_overlap}, an
 * EXCLUDE USING gist constraint — see V29 and {@link MeetingPattern}'s
 * javadoc); a caught {@link DataIntegrityViolationException} is translated
 * into a typed {@code RoomOverlap} result rather than leaking the raw
 * exception. Section tenant validation goes through the shared
 * {@link CourseSectionDirectory} port (implemented by
 * enrollment.CourseSectionService), per the same cycle-avoidance reasoning
 * as {@code shared.directory.FacultyDirectory} — see
 * {@code shared.enrollment}'s package-info for the full justification. Room
 * tenant validation is in-module (both entities live in catalog).
 * <p>
 * {@code @NamedInterface} on this class (Day 8 §13 ceremony): caller is
 * {@code enrollment.application.EnrollmentService}, which must check a
 * candidate section's meeting times against a student's OTHER active
 * enrollments' meeting times for a schedule conflict (day/room conflict is
 * Day 7's concern; THIS is the student-schedule conflict, a distinct check
 * — see that service's javadoc). Only {@link #findActivePatternsForSections}
 * is exposed — a bare projection list, not the {@link MeetingPattern}
 * entity. No reverse edge from catalog into enrollment is created (this is
 * a second, unrelated method on an already-safely-one-directional
 * dependency; the existing {@link CourseSectionDirectory} port is the only
 * catalog-&gt;enrollment edge, unaffected by this). Verified via
 * {@code ModularityTests}.
 */
@org.springframework.modulith.NamedInterface
@Service
public class MeetingPatternService {

    private static final String MANAGE_PERMISSION = "MANAGE_SECTIONS";

    private final MeetingPatternRepository meetingPatternRepository;
    private final MeetingPatternWriteGuard meetingPatternWriteGuard;
    private final RoomRepository roomRepository;
    private final CourseSectionDirectory courseSectionDirectory;
    private final AuthorizationContext authorizationContext;
    private final AuditLogWriter auditLogWriter;

    public MeetingPatternService(MeetingPatternRepository meetingPatternRepository,
                                  MeetingPatternWriteGuard meetingPatternWriteGuard, RoomRepository roomRepository,
                                  CourseSectionDirectory courseSectionDirectory, AuthorizationContext authorizationContext,
                                  AuditLogWriter auditLogWriter) {
        this.meetingPatternRepository = meetingPatternRepository;
        this.meetingPatternWriteGuard = meetingPatternWriteGuard;
        this.roomRepository = roomRepository;
        this.courseSectionDirectory = courseSectionDirectory;
        this.authorizationContext = authorizationContext;
        this.auditLogWriter = auditLogWriter;
    }

    public sealed interface CreateResult {
        record Success(UUID meetingPatternId) implements CreateResult {
        }

        record SectionNotFound() implements CreateResult {
        }

        record RoomNotFound() implements CreateResult {
        }

        record TenantMismatch() implements CreateResult {
        }

        record InvalidPatternData(String message) implements CreateResult {
        }

        record RoomOverlap() implements CreateResult {
        }
    }

    @Transactional
    public CreateResult create(UUID sectionId, short dayOfWeek, LocalTime startTime, LocalTime endTime, UUID roomId,
                                LocalDate effectiveFrom, LocalDate effectiveTo) {
        Optional<UUID> sectionTenantId = courseSectionDirectory.findTenantId(sectionId);
        if (sectionTenantId.isEmpty()) {
            return new CreateResult.SectionNotFound();
        }
        UUID tenantId = sectionTenantId.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("meeting_pattern", UUID.randomUUID(), tenantId));

        Optional<Room> room = roomRepository.findById(roomId);
        if (room.isEmpty()) {
            return new CreateResult.RoomNotFound();
        }
        if (!room.get().tenantId().equals(tenantId)) {
            return new CreateResult.TenantMismatch();
        }

        UUID actor = authorizationContext.currentUserId();
        MeetingPattern pattern;
        try {
            pattern = new MeetingPattern(UUID.randomUUID(), tenantId, sectionId, dayOfWeek, startTime, endTime,
                roomId, effectiveFrom, effectiveTo);
        } catch (IllegalArgumentException e) {
            return new CreateResult.InvalidPatternData(e.getMessage());
        }
        try {
            meetingPatternWriteGuard.saveAndFlush(pattern);
        } catch (DataIntegrityViolationException e) {
            return new CreateResult.RoomOverlap();
        }
        auditLogWriter.write(tenantId, actor, "MEETING_PATTERN_CREATED", "catalog.meeting_patterns", pattern.id(),
            Map.of("sectionId", sectionId, "roomId", roomId, "dayOfWeek", dayOfWeek));
        return new CreateResult.Success(pattern.id());
    }

    @Transactional(readOnly = true)
    public List<MeetingPattern> listForSection(UUID sectionId) {
        return meetingPatternRepository.findBySectionId(sectionId);
    }

    @Transactional(readOnly = true)
    public List<MeetingPattern> listForRoom(UUID roomId) {
        return meetingPatternRepository.findByRoomId(roomId);
    }

    /**
     * Day 9 §2 cross-module port: {@code attendance.application}'s session-open
     * flow must validate that a given meeting_id actually belongs to the
     * section being opened, and belongs to the same tenant — a bare
     * projection, not the {@link MeetingPattern} entity.
     */
    public record MeetingPatternTenantSection(UUID tenantId, UUID sectionId) {
    }

    @Transactional(readOnly = true)
    public Optional<MeetingPatternTenantSection> findTenantAndSection(UUID meetingId) {
        return meetingPatternRepository.findById(meetingId)
            .map(p -> new MeetingPatternTenantSection(p.tenantId(), p.sectionId()));
    }

    /** See this class's javadoc for the full Day 8 §13 cross-module justification. */
    public record MeetingPatternSummary(UUID sectionId, short dayOfWeek, LocalTime startTime, LocalTime endTime,
                                         LocalDate effectiveFrom, LocalDate effectiveTo) {
    }

    @Transactional(readOnly = true)
    public List<MeetingPatternSummary> findActivePatternsForSections(java.util.Collection<UUID> sectionIds) {
        if (sectionIds.isEmpty()) {
            return List.of();
        }
        return meetingPatternRepository.findBySectionIdIn(sectionIds).stream()
            .map(p -> new MeetingPatternSummary(p.sectionId(), p.dayOfWeek(), p.startTime(), p.endTime(),
                p.effectiveFrom(), p.effectiveTo()))
            .toList();
    }

    public sealed interface UpdateResult {
        record Success() implements UpdateResult {
        }

        record NotFound() implements UpdateResult {
        }

        record RoomNotFound() implements UpdateResult {
        }

        record TenantMismatch() implements UpdateResult {
        }

        record InvalidPatternData(String message) implements UpdateResult {
        }

        record RoomOverlap() implements UpdateResult {
        }
    }

    @Transactional
    public UpdateResult update(UUID meetingPatternId, short dayOfWeek, LocalTime startTime, LocalTime endTime,
                                UUID roomId, LocalDate effectiveFrom, LocalDate effectiveTo) {
        Optional<MeetingPattern> maybePattern = meetingPatternRepository.findById(meetingPatternId);
        if (maybePattern.isEmpty()) {
            return new UpdateResult.NotFound();
        }
        MeetingPattern pattern = maybePattern.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("meeting_pattern", pattern.id(), pattern.tenantId()));

        Optional<Room> room = roomRepository.findById(roomId);
        if (room.isEmpty()) {
            return new UpdateResult.RoomNotFound();
        }
        if (!room.get().tenantId().equals(pattern.tenantId())) {
            return new UpdateResult.TenantMismatch();
        }

        UUID actor = authorizationContext.currentUserId();
        try {
            pattern.update(dayOfWeek, startTime, endTime, roomId, effectiveFrom, effectiveTo);
        } catch (IllegalArgumentException e) {
            return new UpdateResult.InvalidPatternData(e.getMessage());
        }
        try {
            meetingPatternWriteGuard.saveAndFlush(pattern);
        } catch (DataIntegrityViolationException e) {
            return new UpdateResult.RoomOverlap();
        }
        auditLogWriter.write(pattern.tenantId(), actor, "MEETING_PATTERN_UPDATED", "catalog.meeting_patterns",
            pattern.id(), Map.of("roomId", roomId, "dayOfWeek", dayOfWeek));
        return new UpdateResult.Success();
    }

    public enum RemoveResult {SUCCESS, NOT_FOUND}

    @Transactional
    public RemoveResult remove(UUID meetingPatternId) {
        Optional<MeetingPattern> maybePattern = meetingPatternRepository.findById(meetingPatternId);
        if (maybePattern.isEmpty()) {
            return RemoveResult.NOT_FOUND;
        }
        MeetingPattern pattern = maybePattern.get();
        authorizationContext.assertCan(MANAGE_PERMISSION, ResourceRef.of("meeting_pattern", pattern.id(), pattern.tenantId()));
        UUID actor = authorizationContext.currentUserId();
        meetingPatternRepository.delete(pattern);
        auditLogWriter.write(pattern.tenantId(), actor, "MEETING_PATTERN_REMOVED", "catalog.meeting_patterns",
            pattern.id(), Map.of("sectionId", pattern.sectionId()));
        return RemoveResult.SUCCESS;
    }

    /** Day 15 §demo-data: plain-UUID unwrap of {@link #create}. */
    @Transactional
    public UUID createForSeed(UUID sectionId, short dayOfWeek, java.time.LocalTime startTime, java.time.LocalTime endTime,
                               UUID roomId, java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo) {
        return switch (create(sectionId, dayOfWeek, startTime, endTime, roomId, effectiveFrom, effectiveTo)) {
            case CreateResult.Success s -> s.meetingPatternId();
            default -> throw new IllegalStateException("seed meeting pattern creation failed for section: " + sectionId);
        };
    }

}
