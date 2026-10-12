package com.setu.record.application;

import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import com.setu.testsupport.Day8TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression: degree progress used to call registrar-only reads (student-program history, curriculum requirement
 * listing) and so returned 403 to the very student it belongs to. Authorization is now decided once, by
 * {@code assertRead}, and the underlying reads are the unchecked ports.
 */
@SpringBootTest
class AcademicRecordServiceTests {

    @Autowired
    Day13TestFixtures day13Fixtures;
    @Autowired
    Day8TestFixtures day8Fixtures;
    @Autowired
    AcademicRecordService academicRecordService;

    @AfterEach
    void clearSecurityContext() {
        TestAuthentication.clear();
    }

    @Test
    void studentCanReadOwnDegreeProgressAndTranscript() {
        var s = day13Fixtures.provisionEnrolledScenario("REC1");
        TestAuthentication.authenticateAs(s.student().userId());

        var progress = academicRecordService.degreeProgress(s.student().studentId(), s.tenant().tenantId());
        var transcript = academicRecordService.transcript(s.student().studentId(), s.tenant().tenantId());

        assertThat(progress.hasActiveProgram()).isTrue();
        assertThat(transcript.lines()).isNotEmpty();
    }

    @Test
    void studentCannotReadAnotherStudentsRecord() {
        var s = day13Fixtures.provisionEnrolledScenario("REC2");
        var other = day8Fixtures.provisionStudentWithActiveProgram(s.tenant(), s.programId(), s.curriculumId(), "REC2O");
        TestAuthentication.authenticateAs(other.userId());

        assertThatThrownBy(() -> academicRecordService.degreeProgress(s.student().studentId(), s.tenant().tenantId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> academicRecordService.transcript(s.student().studentId(), s.tenant().tenantId()))
            .isInstanceOf(AccessDeniedException.class);
    }
}
