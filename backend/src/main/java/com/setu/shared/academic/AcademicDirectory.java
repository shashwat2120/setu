package com.setu.shared.academic;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything the services that sit on top of Academic (learning, assessment, attendance) need to know about Academic's data, and nothing
 * more: who a signed-in user is as a student or faculty member, who is enrolled in and assigned to a section, and a college's time zone.
 * Each question is a narrow lookup with no authorization of its own; the caller has already authorized the operation it is part of.
 * <p>
 * This is the seam the service split runs along. In the monolith {@code enrollment.application.AcademicDirectoryAdapter} answers from
 * Academic's own tables. In an extracted service {@code academic.readmodel.ReadModelAcademicDirectory} answers from that service's local
 * copy of exactly this data, kept current by Academic's events, so no question here ever becomes a network call.
 * <p>
 * Every answer must also be a <i>fact the caller may rely on at this moment</i>: "actively enrolled" and "actively assigned" mean the
 * enrollment or assignment is in force now, not that a row once existed.
 */
public interface AcademicDirectory {

    /** A profile's college and the account that owns it. */
    record TenantOwner(UUID tenantId, UUID ownerUserId) {
    }

    /** What an enrollment is: whose, for which section, in which college. */
    record EnrollmentRef(UUID tenantId, UUID sectionId, UUID studentId) {
    }

    /** What a scheduled meeting belongs to. */
    record MeetingRef(UUID tenantId, UUID sectionId) {
    }

    /** The student profile this account holds in this college, if any. */
    Optional<UUID> studentIdOfUser(UUID tenantId, UUID userId);

    /** A student profile's college and owning account. */
    Optional<TenantOwner> studentOwner(UUID studentId);

    /** The faculty profile this account holds in this college, if any. */
    Optional<UUID> facultyIdOfUser(UUID tenantId, UUID userId);

    /** A faculty profile's college. */
    Optional<UUID> facultyTenant(UUID facultyId);

    /** A faculty profile's status name (for example {@code ACTIVE}); anything but ACTIVE blocks staff self-service. */
    Optional<String> facultyStatus(UUID facultyId);

    /** Whether this faculty member is assigned to teach this section right now. */
    boolean isActivelyAssigned(UUID sectionId, UUID facultyId);

    /** The enrollment that puts this student in this section right now, if any. */
    Optional<UUID> activeEnrollmentId(UUID sectionId, UUID studentId);

    /**
     * The enrollment that can be graded in this section: one in force, or one that is completed (its final grade was published, and late
     * components and corrections of that grade must still be possible). A dropped, waitlisted or rejected enrollment cannot be graded.
     */
    Optional<UUID> gradableEnrollmentId(UUID sectionId, UUID studentId);

    /** An enrollment's student, section and college, whatever its status. */
    Optional<EnrollmentRef> enrollment(UUID enrollmentId);

    /** Every enrollment that is in force in this section right now. */
    List<UUID> activeEnrollmentIds(UUID sectionId);

    /** A scheduled meeting's section and college. */
    Optional<MeetingRef> meeting(UUID meetingPatternId);

    /** A college's time zone; UTC if none or an unusable one is set. */
    ZoneId timeZoneOf(UUID tenantId);
}
