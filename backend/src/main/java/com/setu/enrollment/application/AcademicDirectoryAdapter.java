package com.setu.enrollment.application;

import com.setu.catalog.application.MeetingPatternService;
import com.setu.directory.application.FacultyProfileService;
import com.setu.directory.application.StudentProfileService;
import com.setu.institution.application.CollegeService;
import com.setu.shared.academic.AcademicDirectory;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers {@link AcademicDirectory} from Academic's own services, for the monolith. It lives in {@code enrollment} because that module
 * already sits on top of directory, institution and catalog, so the adapter adds no dependency edge; the modules that ask the questions
 * (learning, assessment, attendance) depend only on the shared port. In an extracted service the same port is answered from a read model.
 */
@Component
class AcademicDirectoryAdapter implements AcademicDirectory {

    private final StudentProfileService students;
    private final FacultyProfileService faculty;
    private final EnrollmentService enrollments;
    private final FacultyAssignmentService assignments;
    private final MeetingPatternService meetings;
    private final CollegeService colleges;

    AcademicDirectoryAdapter(StudentProfileService students, FacultyProfileService faculty, EnrollmentService enrollments,
                             FacultyAssignmentService assignments, MeetingPatternService meetings, CollegeService colleges) {
        this.students = students;
        this.faculty = faculty;
        this.enrollments = enrollments;
        this.assignments = assignments;
        this.meetings = meetings;
        this.colleges = colleges;
    }

    @Override
    public Optional<UUID> studentIdOfUser(UUID tenantId, UUID userId) {
        return students.findIdByTenantAndOwnerUserId(tenantId, userId);
    }

    @Override
    public Optional<TenantOwner> studentOwner(UUID studentId) {
        return students.findTenantAndOwnerUserId(studentId).map(o -> new TenantOwner(o.tenantId(), o.ownerUserId()));
    }

    @Override
    public Optional<UUID> facultyIdOfUser(UUID tenantId, UUID userId) {
        return faculty.findIdByTenantAndOwnerUserId(tenantId, userId);
    }

    @Override
    public Optional<UUID> facultyTenant(UUID facultyId) {
        return faculty.findTenantId(facultyId);
    }

    @Override
    public Optional<String> facultyStatus(UUID facultyId) {
        return faculty.findStatus(facultyId);
    }

    @Override
    public boolean isActivelyAssigned(UUID sectionId, UUID facultyId) {
        return assignments.isActivelyAssigned(sectionId, facultyId);
    }

    @Override
    public Optional<UUID> activeEnrollmentId(UUID sectionId, UUID studentId) {
        return enrollments.findActiveEnrollmentId(sectionId, studentId);
    }

    @Override
    public Optional<EnrollmentRef> enrollment(UUID enrollmentId) {
        return enrollments.findEnrollmentSummary(enrollmentId).map(s -> new EnrollmentRef(s.tenantId(), s.sectionId(), s.studentId()));
    }

    @Override
    public List<UUID> activeEnrollmentIds(UUID sectionId) {
        return enrollments.listActiveEnrollmentIds(sectionId);
    }

    @Override
    public Optional<MeetingRef> meeting(UUID meetingPatternId) {
        return meetings.findTenantAndSection(meetingPatternId).map(m -> new MeetingRef(m.tenantId(), m.sectionId()));
    }

    @Override
    public ZoneId timeZoneOf(UUID tenantId) {
        return colleges.timeZoneFor(tenantId);
    }
}
