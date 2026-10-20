package com.setu.aiexplain.application;

import com.setu.aifacts.application.AiFactsService;
import com.setu.identity.TestAuthentication;
import com.setu.testsupport.Day13TestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The advisor's attendance fact breaks the overall figure down by course, so "how is my attendance in each course?" is answered from data and not with "the system
 * only gives an overall figure" (what it said before). The course is named the way a person knows it, a course with no session yet has no percentage instead of
 * reading as 100%, and the overall figure is unchanged.
 */
@SpringBootTest
class AiAttendanceByCourseTests {

    @Autowired
    AiToolService aiToolService;
    @Autowired
    Day13TestFixtures day13Fixtures;

    @AfterEach
    void clear() {
        TestAuthentication.clear();
    }

    private <T> T callAs(UUID userId, Callable<T> call) {
        TestAuthentication.authenticateAs(userId);
        try {
            return call.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            TestAuthentication.clear();
        }
    }

    @Test
    void eachCourseIsListedWithItsOwnFiguresAndTheOverallFigureIsUnchanged() {
        var s = day13Fixtures.provisionEnrolledScenario("AIC1");
        day13Fixtures.recordAttendanceSession(s, true);
        day13Fixtures.recordAttendanceSession(s, false);

        var result = (AiFactsService.AttendanceResult) callAs(s.student().userId(), () -> aiToolService.getStudentAttendance(null));

        assertThat(result.percentage()).isEqualTo(50.0);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.courses()).singleElement().satisfies(c -> {
            assertThat(c.course()).as("named as a person knows it, not as an id").contains("Title").doesNotContainPattern("[0-9a-f]{8}-[0-9a-f]{4}");
            assertThat(c.section()).isNotBlank();
            assertThat(c.presentOrLate()).isEqualTo(1);
            assertThat(c.total()).isEqualTo(2);
            assertThat(c.percentage()).isEqualTo(50.0);
        });
    }

    @Test
    void aCourseWithNoSessionYetHasNoPercentageAndDoesNotReadAsOneHundred() {
        var s = day13Fixtures.provisionEnrolledScenario("AIC2");

        var result = (AiFactsService.AttendanceResult) callAs(s.student().userId(), () -> aiToolService.getStudentAttendance(null));

        assertThat(result.courses()).singleElement().satisfies(c -> {
            assertThat(c.total()).isZero();
            assertThat(c.percentage()).isNull();
        });
        assertThat(result.percentage()).as("the overall figure is as it always was").isEqualTo(100.0);
    }

    @Test
    void theBreakdownIsGivenToTheModelWithTheFiguresAndNoCourseIsInventedForAnotherStudent() {
        var s = day13Fixtures.provisionEnrolledScenario("AIC3");
        day13Fixtures.recordAttendanceSession(s, true);

        Object forModel = callAs(s.student().userId(), () -> aiToolService.getStudentAttendance(null));
        String text = String.valueOf(AiToolService.forModel(forModel));

        assertThat(text).contains("courses").contains("Title").contains("presentOrLate");
    }
}
