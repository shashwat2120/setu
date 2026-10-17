package com.setu.shared.readmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps a service's local copy of Academic's data current, from the events Academic publishes: who the students and faculty are and which
 * account owns each, who is enrolled in which section, who teaches it, each college's time zone, which meetings a section has, and which
 * guardians may see which student's data.
 * <p>
 * <b>Idempotent</b> (processed-event record) and <b>convergent</b>: every row remembers the time of the event that last wrote it, and an older
 * event never overwrites a newer one, so a replay from the start of a topic, a redelivery, or two events arriving in the opposite order all
 * end in the same state. An event that depends on another having arrived (a status change before the profile it changes) creates the row it
 * needs and lets the earlier event fill in the rest when it comes.
 */
public class AcademicEventProjector extends BaseProjector {

    /** Every event type this projector builds the copy from; the consumer-contract test checks each against what Academic really emits. */
    public static final Set<String> EVENT_TYPES = Set.of("StudentAdmitted", "FacultyProfileCreated", "FacultyStatusChanged", "FacultyAssigned",
        "FacultyAssignmentEnded", "StudentEnrolled", "EnrollmentDropped", "EnrollmentCompleted", "CollegeCreated", "CollegeUpdated", "MeetingPatternScheduled",
        "MeetingPatternRemoved", "GuardianAccessGranted", "GuardianAccessRevoked", "CourseSectionCreated", "CourseSectionOpened", "CourseSectionCancelled");

    public AcademicEventProjector(JdbcClient jdbc, ObjectMapper mapper, String schema) {
        super(jdbc, mapper, schema);
    }

    @Override
    public boolean handles(String eventType) {
        return EVENT_TYPES.contains(eventType);
    }

    @Override
    protected void project(String eventType, EventPayload p) {
        Timestamp at = Timestamp.from(p.instant("occurredAt"));
        switch (eventType) {
            case "StudentAdmitted" -> student(p, at);
            case "FacultyProfileCreated" -> facultyCreated(p, at);
            case "FacultyStatusChanged" -> facultyStatus(p, at);
            case "FacultyAssigned", "FacultyAssignmentEnded" -> assignment(p, at);
            case "StudentEnrolled" -> enrollment(p, at, true, false);
            case "EnrollmentDropped" -> enrollment(p, at, false, false);
            case "EnrollmentCompleted" -> enrollment(p, at, false, true);
            case "CollegeCreated", "CollegeUpdated" -> college(p, at);
            case "MeetingPatternScheduled" -> meeting(p, at, false);
            case "MeetingPatternRemoved" -> meeting(p, at, true);
            case "CourseSectionCreated", "CourseSectionOpened", "CourseSectionCancelled" -> section(p, at);
            case "GuardianAccessGranted" -> grantGranted(p);
            default -> grantRevoked(p, at);
        }
    }

    private void student(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_students") + " AS t (student_id, tenant_id, owner_user_id, updated_at) VALUES (:id, :tenant, :owner, :at) "
                + "ON CONFLICT (student_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, owner_user_id = EXCLUDED.owner_user_id, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("id", p.uuid("studentId")).param("tenant", p.uuid("tenantId")).param("owner", p.optionalUuid("ownerUserId")).param("at", at).update();
    }

    /** The creation event owns the owner and tenant; it must not roll back a status that a later event already set. */
    private void facultyCreated(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_faculty") + " AS t (faculty_id, tenant_id, owner_user_id, status, updated_at) VALUES (:id, :tenant, :owner, :status, :at) "
                + "ON CONFLICT (faculty_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, owner_user_id = EXCLUDED.owner_user_id, "
                + "status = CASE WHEN t.updated_at <= EXCLUDED.updated_at THEN EXCLUDED.status ELSE t.status END, "
                + "updated_at = GREATEST(t.updated_at, EXCLUDED.updated_at)")
            .param("id", p.uuid("facultyId")).param("tenant", p.uuid("tenantId")).param("owner", p.optionalUuid("ownerUserId"))
            .param("status", p.required("status")).param("at", at).update();
    }

    /** A status change keeps the owner already known (or none yet, until the creation event arrives). */
    private void facultyStatus(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_faculty") + " AS t (faculty_id, tenant_id, owner_user_id, status, updated_at) VALUES (:id, :tenant, NULL, :status, :at) "
                + "ON CONFLICT (faculty_id) DO UPDATE SET status = EXCLUDED.status, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("id", p.uuid("facultyId")).param("tenant", p.uuid("tenantId")).param("status", p.required("status")).param("at", at).update();
    }

    private void assignment(EventPayload p, Timestamp at) {
        LocalDate endsAt = p.optionalDate("endsAt");
        jdbc.sql("INSERT INTO " + table("rm_faculty_assignments") + " AS t (assignment_id, tenant_id, section_id, faculty_id, ends_at, updated_at) "
                + "VALUES (:id, :tenant, :section, :faculty, :ends, :at) "
                + "ON CONFLICT (assignment_id) DO UPDATE SET ends_at = EXCLUDED.ends_at, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("id", p.uuid("assignmentId")).param("tenant", p.uuid("tenantId")).param("section", p.uuid("sectionId"))
            .param("faculty", p.uuid("facultyId")).param("ends", endsAt == null ? null : Date.valueOf(endsAt)).param("at", at).update();
    }

    /** {@code active}: in force now. {@code completed}: its final grade was published; not active, but still gradable (a dropped enrollment is neither). */
    private void enrollment(EventPayload p, Timestamp at, boolean active, boolean completed) {
        jdbc.sql("INSERT INTO " + table("rm_enrollments") + " AS t (enrollment_id, tenant_id, section_id, student_id, active, completed, updated_at) "
                + "VALUES (:id, :tenant, :section, :student, :active, :completed, :at) "
                + "ON CONFLICT (enrollment_id) DO UPDATE SET active = EXCLUDED.active, completed = EXCLUDED.completed, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("id", p.uuid("enrollmentId")).param("tenant", p.uuid("tenantId")).param("section", p.uuid("sectionId"))
            .param("student", p.uuid("studentId")).param("active", active).param("completed", completed).param("at", at).update();
    }

    /** A section is known from the moment it is created; opening or cancelling it does not change which college it belongs to. */
    private void section(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_sections") + " AS t (section_id, tenant_id, updated_at) VALUES (:id, :tenant, :at) "
                + "ON CONFLICT (section_id) DO UPDATE SET updated_at = GREATEST(t.updated_at, EXCLUDED.updated_at)")
            .param("id", p.uuid("sectionId")).param("tenant", p.uuid("tenantId")).param("at", at).update();
    }

    private void college(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_colleges") + " AS t (tenant_id, name, timezone, updated_at) VALUES (:tenant, :name, :tz, :at) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET name = EXCLUDED.name, timezone = EXCLUDED.timezone, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("tenant", p.uuid("tenantId")).param("name", p.required("name")).param("tz", p.required("timezone")).param("at", at).update();
    }

    private void meeting(EventPayload p, Timestamp at, boolean removed) {
        jdbc.sql("INSERT INTO " + table("rm_meetings") + " AS t (meeting_id, tenant_id, section_id, removed, updated_at) VALUES (:id, :tenant, :section, :removed, :at) "
                + "ON CONFLICT (meeting_id) DO UPDATE SET removed = EXCLUDED.removed, updated_at = EXCLUDED.updated_at "
                + "WHERE t.updated_at <= EXCLUDED.updated_at")
            .param("id", p.uuid("meetingPatternId")).param("tenant", p.uuid("tenantId")).param("section", p.uuid("sectionId"))
            .param("removed", removed).param("at", at).update();
    }

    /** A grant arriving after its own revocation must not bring it back: the revocation time is kept. The permissions of a grant never change. */
    private void grantGranted(EventPayload p) {
        UUID grantId = p.uuid("grantId");
        Instant expiresAt = p.optionalInstant("expiresAt");
        jdbc.sql("INSERT INTO " + table("rm_guardian_grants") + " AS t (grant_id, tenant_id, student_id, guardian_user_id, expires_at, revoked_at, updated_at) "
                + "VALUES (:id, :tenant, :student, :guardian, :expires, NULL, :at) "
                + "ON CONFLICT (grant_id) DO UPDATE SET guardian_user_id = EXCLUDED.guardian_user_id, expires_at = EXCLUDED.expires_at")
            .param("id", grantId).param("tenant", p.uuid("tenantId")).param("student", p.uuid("studentId"))
            .param("guardian", p.optionalUuid("guardianUserId")).param("expires", expiresAt == null ? null : Timestamp.from(expiresAt))
            .param("at", Timestamp.from(p.instant("occurredAt"))).update();
        for (JsonNode permission : p.array("permissions")) {
            jdbc.sql("INSERT INTO " + table("rm_guardian_grant_permissions") + " (grant_id, permission_code) VALUES (:id, :code) ON CONFLICT DO NOTHING")
                .param("id", grantId).param("code", permission.asText()).update();
        }
    }

    private void grantRevoked(EventPayload p, Timestamp at) {
        jdbc.sql("INSERT INTO " + table("rm_guardian_grants") + " AS t (grant_id, tenant_id, student_id, guardian_user_id, expires_at, revoked_at, updated_at) "
                + "VALUES (:id, :tenant, :student, NULL, NULL, :at, :at) "
                + "ON CONFLICT (grant_id) DO UPDATE SET revoked_at = COALESCE(t.revoked_at, EXCLUDED.revoked_at)")
            .param("id", p.uuid("grantId")).param("tenant", p.uuid("tenantId")).param("student", p.uuid("studentId")).param("at", at).update();
    }
}
