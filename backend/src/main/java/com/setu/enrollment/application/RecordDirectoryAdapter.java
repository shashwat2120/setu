package com.setu.enrollment.application;

import com.setu.catalog.application.CourseService;
import com.setu.catalog.application.CurriculumRequirementService;
import com.setu.enrollment.domain.CourseSection;
import com.setu.enrollment.domain.EnrollmentStatus;
import com.setu.enrollment.infrastructure.CourseSectionRepository;
import com.setu.enrollment.infrastructure.EnrollmentRepository;
import com.setu.shared.academic.RecordDirectory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Answers {@link RecordDirectory} from Academic's own tables and services, for the monolith. It lives in {@code enrollment} for the same reason
 * {@link AcademicDirectoryAdapter} does: this module already sits on top of catalog, so the adapter adds no dependency edge, and the record
 * depends only on the shared port.
 * <p>
 * <b>No authorization here, by the port's contract.</b> The record decides once, in {@code assertRead}, whether the caller may see this
 * student's record. The checked services this adapter replaced decided again, per row, and got it wrong for the cases a record exists for: a
 * student may read a section only while ENROLLED in it, so a COMPLETED course made the student's own transcript and degree progress fail with
 * 403, and a guardian holding a delegated grant has no role in the college that the checked course read demands.
 */
@Component
class RecordDirectoryAdapter implements RecordDirectory {

    private final EnrollmentRepository enrollments;
    private final CourseSectionRepository sections;
    private final CourseService courses;
    private final StudentProgramService programs;
    private final CurriculumRequirementService requirements;

    RecordDirectoryAdapter(EnrollmentRepository enrollments, CourseSectionRepository sections, CourseService courses,
                           StudentProgramService programs, CurriculumRequirementService requirements) {
        this.enrollments = enrollments;
        this.sections = sections;
        this.courses = courses;
        this.programs = programs;
        this.requirements = requirements;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CourseEnrollment> enrollmentsOf(UUID studentId) {
        return enrollments.findByStudentId(studentId, PageRequest.of(0, MAX_ENROLLMENTS)).getContent().stream().map(e -> {
            Optional<CourseSection> section = sections.findById(e.sectionId());
            Optional<CourseService.CourseSummary> course = section.flatMap(s -> courses.summaryUnchecked(s.courseId()));
            // A completed row with no recorded grade (from before final grades were recorded) still counts as passed, as it does for a prerequisite.
            boolean passed = e.status() == EnrollmentStatus.COMPLETED && (e.finalStatus() == null || FinalGrade.isPass(e.finalStatus()));
            return new CourseEnrollment(e.id(), e.status().name(), section.map(CourseSection::sectionCode).orElse(null),
                course.map(CourseService.CourseSummary::code).orElse(null), course.map(CourseService.CourseSummary::title).orElse(null),
                course.map(CourseService.CourseSummary::credits).orElse(null), e.finalStatus(), passed);
        }).toList();
    }

    @Override
    public Optional<UUID> activeCurriculumOf(UUID studentId) {
        return programs.activeCurriculumIdUnchecked(studentId);
    }

    @Override
    public BigDecimal requiredCredits(UUID curriculumId) {
        return requirements.totalMinCreditsUnchecked(curriculumId);
    }
}
