package com.setu.shared.seed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seeded quizzes must be usable: a student who starts an attempt must find real questions, and every question must carry the
 * answer key the grader reads ({@code correctOption} for choice questions, {@code correctAnswer} for short answers). Before this, every
 * seeded assessment had zero questions, so the student's quiz screen was empty.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.demo-data.enabled=true")
class DemoDataSeederQuizContentTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void everySeededAssessmentHasThreeAutoGradableQuestionsWorthItsMarks() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select a.title as title, a.total_marks as total_marks, count(q.id) as questions, coalesce(sum(q.points), 0) as points,
                   count(q.id) filter (where q.type in ('MCQ_SINGLE', 'TRUE_FALSE') and q.metadata_json::text like '%correctOption%') as keyed_choice,
                   count(q.id) filter (where q.type = 'SHORT_ANSWER' and q.metadata_json::text like '%correctAnswer%') as keyed_short
            from assessment.assessments a
            join assessment.assessment_components c on c.assessment_id = a.id
            left join assessment.questions q on q.component_id = c.id
            where a.title in ('Midterm Quiz', 'Data Structures Midterm', 'Reading Response Quiz', 'Final Exam')
            group by a.id, a.title, a.total_marks
            """);

        // The demo data can be seeded more than once in a full test run (the alias test seeds a second copy), so check every copy,
        // and that all three quizzes are present, instead of assuming exactly one row each.
        assertThat(rows).extracting(r -> r.get("title")).as("the seeded quizzes")
            .contains("Midterm Quiz", "Data Structures Midterm", "Reading Response Quiz", "Final Exam");
        for (Map<String, Object> row : rows) {
            long questions = ((Number) row.get("questions")).longValue();
            long keyed = ((Number) row.get("keyed_choice")).longValue() + ((Number) row.get("keyed_short")).longValue();
            assertThat(questions).as("%s has questions", row.get("title")).isEqualTo(3);
            assertThat(keyed).as("%s: every question has an answer key the grader can read", row.get("title")).isEqualTo(questions);
            assertThat(((Number) row.get("points")).doubleValue()).as("%s: question points add up to the quiz's marks", row.get("title"))
                .isEqualTo(((Number) row.get("total_marks")).doubleValue());
        }
    }

    @Test
    void theSeededFinalExamIsItsSectionsOnlyFinalGradeComponentAndIsOpenWithNothingGraded() {
        List<Map<String, Object>> finals = jdbc.queryForList("""
            select a.section_id as section_id, a.title as title, a.status as status,
                   (select count(*) from assessment.grades g where g.component_id = c.id) as grades
            from assessment.assessment_components c
            join assessment.assessments a on a.id = c.assessment_id
            where c.is_final
            """);

        assertThat(finals).as("the demo seeds a final-grade component").isNotEmpty();
        assertThat(finals).extracting(r -> r.get("section_id")).as("one final-grade component per section")
            .doesNotHaveDuplicates();
        assertThat(finals).filteredOn(r -> "Final Exam".equals(r.get("title"))).isNotEmpty().allSatisfy(r -> {
            assertThat(r.get("status")).as("the final exam is open, ready to be graded").isEqualTo("OPEN");
            assertThat(((Number) r.get("grades")).longValue()).as("seeding publishes no final grade, so nobody's enrollment completes").isZero();
        });
    }
}
