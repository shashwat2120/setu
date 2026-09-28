package com.setu.testsupport;

import com.setu.attendance.application.AttendanceRecordService;
import com.setu.attendance.application.AttendanceSessionService;
import com.setu.assessment.application.GradeService;
import com.setu.identity.TestAuthentication;
import com.setu.learning.application.AssignmentSubmissionService;
import com.setu.learning.application.LearningProgressService;
import com.setu.learning.domain.LearningProgressState;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Day 13 test fixtures — builds on Days 7-11's fixtures for a fully
 * enrolled student+faculty+section, and adds the specific data-shaping
 * helpers {@code risk.application.RiskFeatureService} reads from: real
 * attendance sessions (present vs. system-generated absent), real
 * published grades, real assignment submissions (on-time vs. late), and
 * real learning-progress rows. Every helper goes through the actual
 * owning module's application service — never a repository directly —
 * so a Day 13 test's fixture data is exactly as authentic as what a real
 * faculty/student action would produce.
 */
@Component
public class Day13TestFixtures {

    private final Day7TestFixtures day7;
    private final Day8TestFixtures day8;
    private final Day9TestFixtures day9;
    private final Day10TestFixtures day10;
    private final Day11TestFixtures day11;
    private final AttendanceSessionService attendanceSessionService;
    private final AttendanceRecordService attendanceRecordService;
    private final GradeService gradeService;
    private final AssignmentSubmissionService assignmentSubmissionService;
    private final LearningProgressService learningProgressService;

    public Day13TestFixtures(Day7TestFixtures day7, Day8TestFixtures day8, Day9TestFixtures day9,
                              Day10TestFixtures day10, Day11TestFixtures day11,
                              AttendanceSessionService attendanceSessionService, AttendanceRecordService attendanceRecordService,
                              GradeService gradeService, AssignmentSubmissionService assignmentSubmissionService,
                              LearningProgressService learningProgressService) {
        this.day7 = day7;
        this.day8 = day8;
        this.day9 = day9;
        this.day10 = day10;
        this.day11 = day11;
        this.attendanceSessionService = attendanceSessionService;
        this.attendanceRecordService = attendanceRecordService;
        this.gradeService = gradeService;
        this.assignmentSubmissionService = assignmentSubmissionService;
        this.learningProgressService = learningProgressService;
    }

    public record Scenario(Day7TestFixtures.Tenant tenant, UUID programId, UUID curriculumId, UUID courseId,
                            UUID sectionId, UUID meetingId, UUID roomId, Day9TestFixtures.Faculty faculty,
                            Day8TestFixtures.Student student, UUID enrollmentId) {
    }

    /** Tenant -> department -> program -> curriculum -> course -> open section, faculty actively assigned, one student ENROLLED. Everything RiskFeatureService needs as a baseline before any signal-specific data is added. */
    public Scenario provisionEnrolledScenario(String prefix) {
        var tenant = day7.provisionTenant(prefix);
        UUID dept = day7.provisionDepartment(tenant, prefix + "D");
        UUID programId = day7.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = day7.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        UUID courseId = day7.provisionCourse(tenant, dept, prefix + "C");
        UUID periodId = day8.provisionOpenPeriod(tenant, prefix + "PR");
        UUID building = day7.provisionBuilding(tenant, prefix + "B");
        UUID room = day7.provisionRoom(tenant, building, "101", 40);
        UUID sectionId = day8.provisionOpenSection(tenant, courseId, periodId, "S1", 30, room);
        UUID meetingId = day9.provisionMeetingPattern(tenant, sectionId, room);
        var faculty = day9.provisionFacultyAssignedToSection(tenant, sectionId, prefix + "F");
        var student = day8.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID enrollmentId = day9.enrollStudent(student, sectionId);
        return new Scenario(tenant, programId, curriculumId, courseId, sectionId, meetingId, room, faculty, student, enrollmentId);
    }

    /** Opens a session, optionally scans the student in as PRESENT, then closes it — an unscanned student is automatically marked ABSENT at close (state-machines.md #15), exactly like a real missed session. */
    public void recordAttendanceSession(Scenario s, boolean present) {
        TestAuthentication.authenticateAs(s.faculty().userId());
        AttendanceSessionService.OpenResult.Success opened;
        try {
            opened = (AttendanceSessionService.OpenResult.Success)
                attendanceSessionService.open(s.sectionId(), s.meetingId(), Instant.now().plus(1, ChronoUnit.HOURS));
        } finally {
            TestAuthentication.clear();
        }
        if (present) {
            TestAuthentication.authenticateAs(s.student().userId());
            try {
                attendanceRecordService.scan(opened.sessionId(), opened.rawToken());
            } finally {
                TestAuthentication.clear();
            }
        }
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            attendanceSessionService.close(opened.sessionId());
        } finally {
            TestAuthentication.clear();
        }
    }

    /** Creates a fresh open QUIZ assessment+component and publishes one grade for the student on it. */
    public void recordGrade(Scenario s, String gradeCode, BigDecimal gradePoint) {
        var open = day11.provisionOpenAssessment(s.faculty().userId(), s.sectionId(), "Quiz-" + UUID.randomUUID().toString().substring(0, 6));
        TestAuthentication.authenticateAs(s.faculty().userId());
        try {
            var result = gradeService.publishOrCorrect(open.assessmentId(), open.componentId(), s.student().studentId(),
                new BigDecimal("10"), gradeCode, gradePoint);
            if (!(result instanceof GradeService.PublishResult.Success)) {
                throw new IllegalStateException("fixture grade publish failed: " + result);
            }
        } finally {
            TestAuthentication.clear();
        }
    }

    /** Publishes an assignment with the given due offset (negative = already past, so the submission comes back LATE) and submits it as the student. */
    public void recordSubmission(Scenario s, long dueOffsetMinutes) {
        Instant dueAt = Instant.now().plus(dueOffsetMinutes, ChronoUnit.MINUTES);
        UUID assignmentId = day10.provisionPublishedAssignment(s.faculty().userId(), s.sectionId(),
            "Assignment-" + UUID.randomUUID().toString().substring(0, 6), dueAt, null);
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var result = assignmentSubmissionService.submit(assignmentId, "s3://bucket/" + UUID.randomUUID());
            if (!(result instanceof AssignmentSubmissionService.SubmitResult.Success)) {
                throw new IllegalStateException("fixture submission failed: " + result);
            }
        } finally {
            TestAuthentication.clear();
        }
    }

    /** Creates a fresh module+lesson and records one learning_progress row for the student in the given state. */
    public void recordLearningProgress(Scenario s, LearningProgressState state) {
        UUID moduleId = day10.provisionModule(s.faculty().userId(), s.sectionId(), "Module-" + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID lessonId = day10.provisionLesson(s.faculty().userId(), moduleId, "Lesson-" + UUID.randomUUID().toString().substring(0, 6), 1);
        TestAuthentication.authenticateAs(s.student().userId());
        try {
            var result = learningProgressService.record(lessonId, state, state == LearningProgressState.COMPLETED ? new BigDecimal("100") : new BigDecimal("50"), null);
            if (!(result instanceof LearningProgressService.RecordResult.Success)) {
                throw new IllegalStateException("fixture learning progress failed: " + result);
            }
        } finally {
            TestAuthentication.clear();
        }
    }
}
