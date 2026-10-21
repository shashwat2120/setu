package com.setu.career.application;

import com.setu.career.domain.JobTerms;
import com.setu.career.domain.ResumeContent;
import com.setu.career.domain.ResumePdfTests;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PDF a student uploads as their resume: kept outside the database under its hash, readable only by its student, frozen into each application made with it (so replacing
 * or removing it later changes nothing for applications already made), and handed to an officer only through the application, with the download audited. Not
 * {@code @Transactional}: applying uses its own transaction.
 */
@SpringBootTest
class ResumeFileTests {

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
    ResumeFileStore fileStore;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private record Setup(Day7TestFixtures.Tenant tenant, Day8TestFixtures.Student student, UUID officer, UUID jobId, UUID programId, UUID curriculumId) {
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
        return new Setup(tenant, student, officer, jobId, programId, curriculumId);
    }

    private <T> T asStudent(Setup s, java.util.function.Supplier<T> action) {
        TestAuthentication.authenticateAs(s.student.userId());
        try {
            return action.get();
        } finally {
            TestAuthentication.clear();
        }
    }

    private ResumeService.UploadResult upload(Setup s, String name, byte[] bytes) {
        return asStudent(s, () -> resumeService.uploadFile(s.student.studentId(), name, bytes));
    }

    private UUID apply(Setup s) {
        return ((ApplicationService.ApplyResult.Success) asStudent(s, () -> applicationService.apply(s.jobId, s.student.studentId()))).applicationId();
    }

    @Test
    void aStudentUploadsAPdfAndGetsExactlyThoseBytesBackAndTheDatabaseKeepsOnlyAReference() {
        var s = setUp("RF1", false);
        byte[] bytes = ResumePdfTests.pdf("/Title (My resume)");

        var stored = (ResumeService.UploadResult.Stored) upload(s, "C:\\docs\\My Resume.PDF", bytes);

        assertThat(stored.file().name()).isEqualTo("My Resume.pdf");
        assertThat(stored.file().size()).isEqualTo(bytes.length);
        assertThat(stored.version()).isEqualTo(1);
        var view = asStudent(s, () -> resumeService.viewOwn(s.student.studentId()).orElseThrow());
        assertThat(view.file()).isEqualTo(stored.file());
        assertThat(asStudent(s, () -> resumeService.downloadOwnFile(s.student.studentId()).orElseThrow()).bytes()).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("SELECT file_sha256 FROM career.student_resumes WHERE student_id = ?", String.class, s.student.studentId())).isEqualTo(stored.file().sha256());
        assertThat(jdbc.queryForObject("SELECT content::text FROM career.student_resumes WHERE student_id = ?", String.class, s.student.studentId())).doesNotContain("%PDF");
    }

    @Test
    void aFileThatIsNotAnAcceptablePdfIsRefusedAndNothingIsStored() {
        var s = setUp("RF2", false);

        var notPdf = upload(s, "resume.pdf", "this is plain text, not a pdf at all, whatever the name says".getBytes());
        var script = upload(s, "resume.pdf", ResumePdfTests.pdf("/JavaScript 5 0 R"));

        assertThat(notPdf).isInstanceOf(ResumeService.UploadResult.Invalid.class);
        assertThat(script).isInstanceOf(ResumeService.UploadResult.Invalid.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM career.student_resumes WHERE student_id = ?", Long.class, s.student.studentId())).isZero();
    }

    @Test
    void savingTheFormLeavesTheFileAloneAndUploadingLeavesTheFormAlone() {
        var s = setUp("RF3", false);
        asStudent(s, () -> resumeService.save(s.student.studentId(), new ResumeContent("Aspiring analyst", null, null, null, null, null), null));
        var stored = (ResumeService.UploadResult.Stored) upload(s, "cv.pdf", ResumePdfTests.pdf(""));

        assertThat(stored.version()).isEqualTo(2);
        asStudent(s, () -> resumeService.save(s.student.studentId(), new ResumeContent("Data analyst", null, null, null, null, null), 2));
        var view = asStudent(s, () -> resumeService.viewOwn(s.student.studentId()).orElseThrow());
        assertThat(view.content().headline()).isEqualTo("Data analyst");
        assertThat(view.file().name()).isEqualTo("cv.pdf");
    }

    @Test
    void nobodyButTheStudentUploadsReadsOrRemovesTheirFile() {
        var s = setUp("RF4", false);
        upload(s, "cv.pdf", ResumePdfTests.pdf(""));
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "RF4O");

        for (UUID user : new UUID[] {s.officer, other.userId()}) {
            TestAuthentication.authenticateAs(user);
            assertThatThrownBy(() -> resumeService.uploadFile(s.student.studentId(), "x.pdf", ResumePdfTests.pdf(""))).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> resumeService.downloadOwnFile(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> resumeService.removeFile(s.student.studentId())).isInstanceOf(AccessDeniedException.class);
            TestAuthentication.clear();
        }
    }

    @Test
    void aStudentWithOnlyAPdfHasAResumeForAJobThatNeedsOne() {
        var s = setUp("RF5", true);

        assertThat(asStudent(s, () -> applicationService.apply(s.jobId, s.student.studentId()))).isInstanceOf(ApplicationService.ApplyResult.NotEligible.class);
        upload(s, "cv.pdf", ResumePdfTests.pdf(""));

        assertThat(asStudent(s, () -> applicationService.apply(s.jobId, s.student.studentId()))).isInstanceOf(ApplicationService.ApplyResult.Success.class);
    }

    @Test
    void anApplicationKeepsTheExactFileItWasMadeWithEvenAfterTheStudentReplacesOrRemovesIt() {
        var s = setUp("RF6", false);
        byte[] original = ResumePdfTests.pdf("/Title (as submitted)");
        upload(s, "original.pdf", original);
        UUID applicationId = apply(s);
        upload(s, "replacement.pdf", ResumePdfTests.pdf("/Title (a later version)"));
        asStudent(s, () -> resumeService.removeFile(s.student.studentId()));

        TestAuthentication.authenticateAs(s.officer);
        var snapshot = (ApplicationService.ResumeResult.Found) applicationService.resumeOf(applicationId);
        var file = (ApplicationService.ResumeFileResult.Found) applicationService.resumeFileOf(applicationId);

        assertThat(snapshot.snapshot().file().name()).isEqualTo("original.pdf");
        assertThat(file.file().name()).isEqualTo("original.pdf");
        assertThat(file.file().bytes()).isEqualTo(original);
        assertThat(asStudent(s, () -> resumeService.downloadOwnFile(s.student.studentId()))).as("the student's current resume has no file now").isEmpty();
    }

    @Test
    void theOfficersDownloadIsAuditedTheStudentsOwnIsNotAndNoOneElseGetsTheFile() {
        var s = setUp("RF7", false);
        upload(s, "cv.pdf", ResumePdfTests.pdf(""));
        UUID applicationId = apply(s);
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant, s.programId, s.curriculumId, "RF7O");
        UUID otherOfficer = day12Fixtures.provisionPlacementOfficer(fixtures.provisionTenant("RF7B"), "RF7BPO");

        TestAuthentication.authenticateAs(s.officer);
        assertThat(applicationService.resumeFileOf(applicationId)).isInstanceOf(ApplicationService.ResumeFileResult.Found.class);
        TestAuthentication.clear();
        assertThat(asStudent(s, () -> applicationService.resumeFileOf(applicationId))).isInstanceOf(ApplicationService.ResumeFileResult.Found.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM governance.audit_logs WHERE action = 'RESUME_FILE_DOWNLOADED' AND resource_id = ?", Long.class, applicationId)).isEqualTo(1L);

        for (UUID user : new UUID[] {other.userId(), otherOfficer}) {
            TestAuthentication.authenticateAs(user);
            assertThatThrownBy(() -> applicationService.resumeFileOf(applicationId)).isInstanceOf(AccessDeniedException.class);
            TestAuthentication.clear();
        }
    }

    @Test
    void anApplicationMadeWithoutAFileHasNoneToDownload() {
        var s = setUp("RF8", false);
        asStudent(s, () -> resumeService.save(s.student.studentId(), new ResumeContent("Aspiring analyst", null, null, null, null, null), null));
        UUID applicationId = apply(s);

        TestAuthentication.authenticateAs(s.officer);

        assertThat(applicationService.resumeFileOf(applicationId)).isInstanceOf(ApplicationService.ResumeFileResult.None.class);
    }

    @Test
    void theStoreWillNotBeAskedForAnythingThatIsNotAHashInAnotherCollegesDirectory() throws Exception {
        var s = setUp("RF9", false);

        for (String bad : new String[] {"../../etc/passwd", "..", "ABCDEF", "0".repeat(63), "0".repeat(65), "z".repeat(64), "0".repeat(60) + "/../"}) {
            assertThatThrownBy(() -> fileStore.get(s.tenant.tenantId(), bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        var stored = (ResumeService.UploadResult.Stored) upload(s, "cv.pdf", ResumePdfTests.pdf(""));
        assertThat(fileStore.get(UUID.randomUUID(), stored.file().sha256())).as("another college's directory has no such file").isEmpty();
    }
}
