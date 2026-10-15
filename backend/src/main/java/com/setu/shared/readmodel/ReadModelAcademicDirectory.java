package com.setu.shared.readmodel;

import com.setu.shared.academic.AcademicDirectory;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AcademicDirectory} for an extracted service: every answer comes from that service's own copy of Academic's data
 * ({@code <schema>.rm_*}), never from a call to Academic. The copy is built from Academic's events by {@link AcademicEventProjector}.
 * <p>
 * One difference from the monolith is deliberate and visible: the copy holds an enrollment only once it has been ENROLLED (the first event
 * that mentions it), so {@link #enrollment} knows enrollments that are or were in force, not requests that were refused or are still waiting.
 * No caller asks about those.
 */
public class ReadModelAcademicDirectory implements AcademicDirectory {

    private final JdbcClient jdbc;
    private final String students;
    private final String faculty;
    private final String assignments;
    private final String enrollments;
    private final String colleges;
    private final String meetings;

    public ReadModelAcademicDirectory(JdbcClient jdbc, String schema) {
        this.jdbc = jdbc;
        this.students = ReadModelSchema.table(schema, "rm_students");
        this.faculty = ReadModelSchema.table(schema, "rm_faculty");
        this.assignments = ReadModelSchema.table(schema, "rm_faculty_assignments");
        this.enrollments = ReadModelSchema.table(schema, "rm_enrollments");
        this.colleges = ReadModelSchema.table(schema, "rm_colleges");
        this.meetings = ReadModelSchema.table(schema, "rm_meetings");
    }

    @Override
    public Optional<UUID> studentIdOfUser(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT student_id FROM " + students + " WHERE tenant_id = :tenant AND owner_user_id = :user")
            .param("tenant", tenantId).param("user", userId).query(UUID.class).optional();
    }

    @Override
    public Optional<TenantOwner> studentOwner(UUID studentId) {
        return jdbc.sql("SELECT tenant_id, owner_user_id FROM " + students + " WHERE student_id = :id")
            .param("id", studentId)
            .query((rs, i) -> new TenantOwner(rs.getObject("tenant_id", UUID.class), rs.getObject("owner_user_id", UUID.class)))
            .optional();
    }

    @Override
    public Optional<UUID> facultyIdOfUser(UUID tenantId, UUID userId) {
        return jdbc.sql("SELECT faculty_id FROM " + faculty + " WHERE tenant_id = :tenant AND owner_user_id = :user")
            .param("tenant", tenantId).param("user", userId).query(UUID.class).optional();
    }

    @Override
    public Optional<UUID> facultyTenant(UUID facultyId) {
        return jdbc.sql("SELECT tenant_id FROM " + faculty + " WHERE faculty_id = :id").param("id", facultyId).query(UUID.class).optional();
    }

    @Override
    public Optional<String> facultyStatus(UUID facultyId) {
        return jdbc.sql("SELECT status FROM " + faculty + " WHERE faculty_id = :id").param("id", facultyId).query(String.class).optional();
    }

    @Override
    public boolean isActivelyAssigned(UUID sectionId, UUID facultyId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + assignments + " WHERE section_id = :section AND faculty_id = :faculty AND ends_at IS NULL)")
            .param("section", sectionId).param("faculty", facultyId).query(Boolean.class).single();
    }

    @Override
    public Optional<UUID> activeEnrollmentId(UUID sectionId, UUID studentId) {
        return jdbc.sql("SELECT enrollment_id FROM " + enrollments + " WHERE section_id = :section AND student_id = :student AND active LIMIT 1")
            .param("section", sectionId).param("student", studentId).query(UUID.class).optional();
    }

    @Override
    public Optional<EnrollmentRef> enrollment(UUID enrollmentId) {
        return jdbc.sql("SELECT tenant_id, section_id, student_id FROM " + enrollments + " WHERE enrollment_id = :id")
            .param("id", enrollmentId)
            .query((rs, i) -> new EnrollmentRef(rs.getObject("tenant_id", UUID.class), rs.getObject("section_id", UUID.class),
                rs.getObject("student_id", UUID.class)))
            .optional();
    }

    @Override
    public List<UUID> activeEnrollmentIds(UUID sectionId) {
        return jdbc.sql("SELECT enrollment_id FROM " + enrollments + " WHERE section_id = :section AND active ORDER BY enrollment_id")
            .param("section", sectionId).query(UUID.class).list();
    }

    @Override
    public Optional<MeetingRef> meeting(UUID meetingPatternId) {
        return jdbc.sql("SELECT tenant_id, section_id FROM " + meetings + " WHERE meeting_id = :id AND NOT removed")
            .param("id", meetingPatternId)
            .query((rs, i) -> new MeetingRef(rs.getObject("tenant_id", UUID.class), rs.getObject("section_id", UUID.class)))
            .optional();
    }

    @Override
    public ZoneId timeZoneOf(UUID tenantId) {
        return jdbc.sql("SELECT timezone FROM " + colleges + " WHERE tenant_id = :id").param("id", tenantId).query(String.class).optional()
            .map(tz -> {
                try {
                    return ZoneId.of(tz);
                } catch (DateTimeException e) {
                    return (ZoneId) ZoneOffset.UTC;
                }
            })
            .orElse(ZoneOffset.UTC);
    }
}
