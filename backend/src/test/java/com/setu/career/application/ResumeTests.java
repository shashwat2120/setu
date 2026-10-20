package com.setu.career.application;

import com.setu.career.domain.JobTerms;
import com.setu.career.domain.ResumeContent;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day12TestFixtures;
import com.setu.testsupport.Day7TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A student's resume and the resume an application is made with: bounded and safe to show, readable and editable only by its student, frozen into the application when
 * they apply, required by the jobs that say so, and read by a placement officer only through an application, with the reading audited. Not {@code @Transactional}:
 * applying uses its own transaction.
 */
@SpringBootTest
class ResumeTests {

    @Autowired
    Day7TestFixtures fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    Day12TestFixtures day12Fixtures;
    @Autowired
    JobService jobService;
    @Autowired
    ResumeService resumeService;
    @Autowired
    ApplicationService applicationService;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private static ResumeContent resume(String headline) {
        return new ResumeContent(headline, "Third-year student who likes data", "https://www.linkedin.com/in/example", null, null,
            List.of(new ResumeContent.Item("project", "Attendance dashboard", "College", "Built a dashboard", LocalDate.of(2026, 1, 1), null)));
    }

    // ---- the content itself

    @Test
    void blanksBecomeNothingAndAHeadlineMakesAResumeComplete() {
        var content = new ResumeContent("  ", " ", "", null, "  ", null);

        assertThat(content.headline()).isNull();
        assertThat(content.items()).isEmpty();
        assertThat(content.complete()).isFalse();
        assertThat(resume("Aspiring analyst").complete()).isTrue();
        assertThat(resume("Aspiring analyst").items().get(0).kind()).isEqualTo("PROJECT");
    }

    @Test
    void onlyHttpsLinksAreAcceptedSoNothingExecutableIsEverShownAsALink() {
        for (String bad : List.of("javascript:alert(1)", "http://example.com", "ftp://example.com/x", "file:///etc/passwd", "https://", "https://exa mple.com", "example.com")) {
            var content = new ResumeContent("h", null, null, bad, null, null);
            assertThatThrownBy(content::validate).as(bad).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https link");
        }
        new ResumeContent("h", null, "https://www.linkedin.com/in/x", "https://github.com/x", "https://x.example.com/portfolio?a=1", null).validate();
    }

    @Test
    void everyFieldIsBoundedAndEntriesAreChecked() {
        assertThatThrownBy(() -> new ResumeContent("x".repeat(121), null, null, null, null, null).validate()).hasMessageContaining("headline");
        assertThatThrownBy(() -> new ResumeContent("h", "x".repeat(1001), null, null, null, null).validate()).hasMessageContaining("summary");
        var many = java.util.stream.IntStream.range(0, 21).mapToObj(i -> new ResumeContent.Item("PROJECT", "p" + i, null, null, null, null)).toList();
        assertThatThrownBy(() -> new ResumeContent("h", null, null, null, null, many).validate()).hasMessageContaining("at most 20");
        assertThatThrownBy(() -> new ResumeContent("h", null, null, null, null, List.of(new ResumeContent.Item("HOBBY", "t", null, null, null, null))).validate())
            .hasMessageContaining("project, a certification or an experience");
        assertThatThrownBy(() -> new ResumeContent("h", null, null, null, null, List.of(new ResumeContent.Item("PROJECT", null, null, null, null, null))).validate())
            .hasMessageContaining("needs a title");
        assertThatThrownBy(() -> new ResumeContent("h", null, null, null, null,
            List.of(new ResumeContent.Item("PROJECT", "t", null, null, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)))).validate()).hasMessageContaining("before it starts");
        assertThatThrownBy(() -> new ResumeContent("h", null, null, null, null,
            List.of(new ResumeContent.Item("PROJECT", "t", null, null, LocalDate.of(1800, 1, 1), null))).validate()).hasMessageContaining("plausible");
    }

    // ---- the student's own resume

    private record Setup(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID officer, UUID jobId) {
    }

    private Setup setUp(String prefix, boolean jobNeedsResume) {
        var tenant = fixtures.provisionTenant(prefix);
        UUID dept = fixtures.provisionDepartment(tenant, prefix + "D");
        UUID programId = fixtures.provisionProgram(tenant, dept, prefix + "P");
        UUID curriculumId = fixtures.provisionCurriculum(tenant, programId, "v1", LocalDate.of(2025, 1, 1), null);
        var student = day8Fixtures.provisionStudentWithActiveProgram(tenant, programId, curriculumId, prefix + "S");
        UUID officer = day12Fixtures.provisionPlacementOfficer(tenant, prefix + "PO");
        TestAuthentication.authenticateAs(officer);
        UUID jobId = ((JobService.CreateResult.Success) jobService.create(tenant.tenantId(), "Analyst", "Example Co", "d",
            new JobTerms(null, null, null, null, null, null, null, null, null, jobNeedsResume))).jobId();
        TestAuthentication.clear();
        return new Setup(tenant, student, officer, jobId);
    }

    private ResumeService.SaveResult saveAs(Setup s, ResumeContent content, Integer expectedVersion) {
        TestAuthentication.authenticateAs(s.student.userId());
        try {
            return resumeService.save(s.student.studentId(), content, expectedVersion);
        } finally {
            TestAuthentication.clear();
        }
    }

    private ApplicationService.ApplyResult applyAs(Setup s) {
        TestAuthentication.authenticateAs(s.student.userId());
        try {
            return applicationService.apply(s.jobId, s.student.studentId());
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void aStudentSavesAndReadsTheirOwnResumeAndAStaleSaveIsAConflict() {
        var s = setUp("RS1", false);

        assertThat(saveAs(s, resume("Aspiring analyst"), null)).isEqualTo(new ResumeService.SaveResult.Saved(1));
        TestAuthentication.authenticateAs(s.student.userId());
        var view = resumeService.viewOwn(s.student.studentId()).orElseThrow();
        assertThat(view.content().headline()).isEqualTo("Aspiring analyst");
        assertThat(view.version()).isEqualTo(1);
        TestAuthentication.clear();

        assertThat(saveAs(s, resume("Data analyst"), 1)).isEqualTo(new ResumeService.SaveResult.Saved(2));
        assertThat(saveAs(s, resume("Overwrites a change it never saw"), 1)).isEqualTo(new ResumeService.SaveResult.Conflict());
        // two windows both opened on an empty resume: the second to save is told, not allowed to overwrite the first
        assertThat(saveAs(s, resume("A second window that opened on an empty resume"), 0)).isEqualTo(new ResumeService.SaveResult.Conflict());
        assertThat(saveAs(s, resume("A first save claiming an old version"), 5)).isEqualTo(new ResumeService.SaveResult.Conflict());
        TestAuthentication.authenticateAs(s.student.userId());
        assertThat(resumeService.viewOwn(s.student.studentId()).orElseThrow().content().headline()).isEqualTo("Data analyst");
    }

    @Test
    void aStudentWithNoResumeYetSeesAnEmptyOneAtVersionZero() {
        var s = setUp("RS2", false);
        TestAuthentication.authenticateAs(s.student.userId());

        var view = resumeService.viewOwn(s.student.studentId()).orElseThrow();

        assertThat(view.version()).isZero();
        assertThat(view.content().complete()).isFalse();
        assertThat(view.updatedAt()).isNull();
    }

    @Test
    void anInvalidResumeIsRefusedAndNothingIsStored() {
        var s = setUp("RS3", false);

        var result = saveAs(s, new ResumeContent("h", null, "javascript:alert(1)", null, null, null), null);

        assertThat(result).isInstanceOf(ResumeService.SaveResult.Invalid.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career.student_resumes WHERE student_id = ?", Long.class, s.student.studentId())).isZero();
    }

    @Test
    void nobodyButTheStudentReadsOrEditsTheirResumeNotAnOfficerNotAnotherStudent() {
        var s = setUp("RS4", false);
        saveAs(s, resume("Aspiring analyst"), null);
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant,
            jdbc.queryForObject("SELECT program_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.student.studentId()),
            jdbc.queryForObject("SELECT curriculum_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.student.studentId()), "RS4O");

        TestAuthentication.authenticateAs(s.officer);
        assertThatThrownBy(() -> resumeService.viewOwn(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resumeService.save(s.student.studentId(), resume("Edited by an officer"), 1)).isInstanceOf(AccessDeniedException.class);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(other.userId());
        assertThatThrownBy(() -> resumeService.viewOwn(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> resumeService.save(s.student.studentId(), resume("Edited by a classmate"), 0)).isInstanceOf(AccessDeniedException.class);
    }

    // ---- applying with a resume

    @Test
    void anApplicationKeepsTheResumeItWasMadeWithEvenAfterTheStudentEditsIt() {
        var s = setUp("RS5", false);
        saveAs(s, resume("Aspiring analyst"), null);
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applyAs(s)).applicationId();
        saveAs(s, resume("Rewritten after applying"), 1);

        TestAuthentication.authenticateAs(s.officer);
        var found = (ApplicationService.ResumeResult.Found) applicationService.resumeOf(applicationId);

        assertThat(found.snapshot().content().headline()).isEqualTo("Aspiring analyst");
        assertThat(found.snapshot().resumeVersion()).isEqualTo(1);
        assertThat(found.snapshot().content().items()).singleElement().satisfies(i -> assertThat(i.title()).isEqualTo("Attendance dashboard"));
        assertThat(found.snapshot().capturedAt()).isNotNull();
        assertThat(found.snapshot().record().expectedGraduationYear()).isEqualTo(2028);
        assertThat(jdbc.queryForObject("SELECT resume_version FROM career.applications WHERE id = ?", Integer.class, applicationId)).isEqualTo(1);
    }

    @Test
    void aStudentWithNoResumeCanStillApplyToAJobThatDoesNotNeedOneAndItSaysNoResumeWasAttached() {
        var s = setUp("RS6", false);
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applyAs(s)).applicationId();

        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.resumeOf(applicationId)).isInstanceOf(ApplicationService.ResumeResult.None.class);
        assertThat(jdbc.queryForObject("SELECT resume_snapshot IS NULL FROM career.applications WHERE id = ?", Boolean.class, applicationId)).isTrue();
    }

    @Test
    void aJobThatNeedsAResumeRefusesAStudentWithoutOneAndSaysWhyUntilTheyHaveAHeadline() {
        var s = setUp("RS7", true);

        var refused = applyAs(s);
        assertThat(refused).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);
        assertThat(((ApplicationService.ApplyResult.NotEligible) refused).unmetRules()).singleElement().satisfies(r -> {
            assertThat(r.code()).isEqualTo(EligibilityRules.RESUME_REQUIRED);
            assertThat(r.message()).contains("needs a resume");
        });

        // a summary alone is not a resume: the headline is the least an officer needs
        saveAs(s, new ResumeContent(null, "Only a summary", null, null, null, null), null);
        assertThat(applyAs(s)).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);

        saveAs(s, resume("Aspiring analyst"), 1);
        assertThat(applyAs(s)).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    // ---- the officer reads it through the application

    @Test
    void anOfficerReadsTheResumeThroughTheApplicationAndTheReadingIsAudited() {
        var s = setUp("RS8", false);
        saveAs(s, resume("Aspiring analyst"), null);
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applyAs(s)).applicationId();
        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.resumeOf(applicationId)).isInstanceOf(ApplicationService.ResumeResult.Found.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM governance.audit_logs WHERE action = 'RESUME_VIEWED' AND resource_id = ?", Long.class, applicationId)).isEqualTo(1L);
    }

    @Test
    void theStudentReadsTheirOwnApplicationsResumeWithoutAnAuditEntryAndNoOneElseDoes() {
        var s = setUp("RS9", false);
        saveAs(s, resume("Aspiring analyst"), null);
        UUID applicationId = ((ApplicationService.ApplyResult.Success) applyAs(s)).applicationId();
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant,
            jdbc.queryForObject("SELECT program_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.student.studentId()),
            jdbc.queryForObject("SELECT curriculum_id FROM enrollment.student_programs WHERE student_id = ?", UUID.class, s.student.studentId()), "RS9O");
        var otherTenant = fixtures.provisionTenant("RS9B");
        UUID otherOfficer = day12Fixtures.provisionPlacementOfficer(otherTenant, "RS9BPO");

        TestAuthentication.authenticateAs(s.student.userId());
        assertThat(applicationService.resumeOf(applicationId)).isInstanceOf(ApplicationService.ResumeResult.Found.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM governance.audit_logs WHERE action = 'RESUME_VIEWED' AND resource_id = ?", Long.class, applicationId)).isZero();
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(other.userId());
        assertThatThrownBy(() -> applicationService.resumeOf(applicationId)).isInstanceOf(AccessDeniedException.class);
        TestAuthentication.clear();
        TestAuthentication.authenticateAs(otherOfficer);
        assertThatThrownBy(() -> applicationService.resumeOf(applicationId)).isInstanceOf(AccessDeniedException.class);
    }
}
